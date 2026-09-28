package com.lbz.f1aipredict.prediction.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.Failure;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.Result;
import com.lbz.f1aipredict.prediction.inbound.PredictionOutcomePersistenceService.OutcomeApplyResult;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** 结果与失败分别监听；持久化/隔离提交成功后才 ACK，暂态故障关闭通道重投。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "f1predict.prediction.outcome", name = "enabled", havingValue = "true")
public class PredictionOutcomeConsumer {

    private static final String RESULT_LISTENER_ID = "predictionOutcomeResultListener";
    private static final String FAILURE_LISTENER_ID = "predictionOutcomeFailureListener";

    private final OutcomeMessageParser parser;
    private final PredictionOutcomePersistenceService persistenceService;
    private final PredictionOutcomeProperties properties;
    private final RabbitListenerEndpointRegistry registry;
    private final ObjectMapper objectMapper;
    private final AtomicInteger resultFailures = new AtomicInteger();
    private final AtomicInteger failureFailures = new AtomicInteger();

    public PredictionOutcomeConsumer(OutcomeMessageParser parser,
                                     PredictionOutcomePersistenceService persistenceService,
                                     PredictionOutcomeProperties properties,
                                     RabbitListenerEndpointRegistry registry,
                                     ObjectMapper objectMapper) {
        this.parser = parser;
        this.persistenceService = persistenceService;
        this.properties = properties;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(id = RESULT_LISTENER_ID, queues = "#{@predictionOutcomeProperties.resultQueue}",
            containerFactory = "predictionOutcomeListenerContainerFactory")
    public void onResult(Message message, Channel channel) {
        consume(message, channel, true);
    }

    @RabbitListener(id = FAILURE_LISTENER_ID, queues = "#{@predictionOutcomeProperties.failureQueue}",
            containerFactory = "predictionOutcomeListenerContainerFactory")
    public void onFailure(Message message, Channel channel) {
        consume(message, channel, false);
    }

    private void consume(Message message, Channel channel, boolean resultMessage) {
        byte[] body = message == null ? null : message.getBody();
        AtomicInteger failures = resultMessage ? resultFailures : failureFailures;
        String listenerId = resultMessage ? RESULT_LISTENER_ID : FAILURE_LISTENER_ID;
        if (body == null || body.length > properties.getQuarantineMaxBodyBytes()) {
            log.error("预测终态消息超过隔离上限，已暂停消费队列: result={}", resultMessage);
            stopAndClose(listenerId, channel);
            return;
        }
        int maxBytes = resultMessage ? properties.getResultMaxMessageBytes() : properties.getFailureMaxMessageBytes();
        String messageId = body.length <= maxBytes ? extractMessageId(body) : null;
        try {
            if (body.length > maxBytes) {
                persistenceService.quarantine(body, messageId, "MESSAGE_SIZE_INVALID");
                acknowledge(message, channel, failures);
                return;
            }
            if (resultMessage) {
                Result result = parser.parseResult(body, maxBytes);
                OutcomeApplyResult applied = persistenceService.applyResult(result, canonicalHash(result));
                log.info("已持久化预测终态: messageId={}, outcome=RESULT, duplicate={}",
                        result.getMessageId(), applied == OutcomeApplyResult.DUPLICATE);
            } else {
                Failure failure = parser.parseFailure(body, maxBytes);
                OutcomeApplyResult applied = persistenceService.applyFailure(failure, canonicalHash(failure));
                log.info("已持久化预测终态: messageId={}, outcome=FAILURE, duplicate={}",
                        failure.getMessageId(), applied == OutcomeApplyResult.DUPLICATE);
            }
            acknowledge(message, channel, failures);
        } catch (OutcomePoisonMessageException poison) {
            try {
                persistenceService.quarantine(body, messageId, poison.getReasonCode());
                log.warn("预测终态消息已隔离: messageId={}, reasonCode={}", messageId, poison.getReasonCode());
                acknowledge(message, channel, failures);
            } catch (RuntimeException | IOException error) {
                transientFailure(listenerId, channel, failures, messageId, error);
            }
        } catch (RuntimeException | IOException error) {
            transientFailure(listenerId, channel, failures, messageId, error);
        }
    }

    private String canonicalHash(Object dto) {
        try {
            return OutcomeMessageParser.sha256(objectMapper.writeValueAsBytes(dto));
        } catch (Exception error) {
            throw new IllegalStateException("Outcome digest generation failed", error);
        }
    }

    private void acknowledge(Message message, Channel channel, AtomicInteger failures) throws IOException {
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        failures.set(0);
    }

    private void transientFailure(String listenerId, Channel channel, AtomicInteger failures,
                                  String messageId, Throwable error) {
        log.error("预测终态写入暂时失败，未确认消息并关闭通道: messageId={}, errorType={}",
                messageId, error.getClass().getSimpleName());
        closeQuietly(channel);
        backoff(failures);
    }

    private void stopAndClose(String listenerId, Channel channel) {
        MessageListenerContainer container = registry.getListenerContainer(listenerId);
        if (container != null && container.isRunning()) {
            container.stop(() -> log.error("预测终态队列消费者已暂停，需人工处置超限消息: listenerId={}", listenerId));
        }
        closeQuietly(channel);
    }

    private void backoff(AtomicInteger failures) {
        int attempt = Math.min(10, failures.incrementAndGet());
        long delay = Math.min(properties.getRetryBackoffMaxSeconds(), 1L << Math.min(attempt - 1, 10));
        try {
            Thread.sleep(Math.max(1, delay) * 1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Channel channel) {
        try {
            channel.abort();
        } catch (IOException ignored) {
            // 通道已关闭时，Broker 会按未确认交付重新排队。
        }
    }

    private String extractMessageId(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            JsonNode node = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
            JsonNode id = node == null ? null : node.get("messageId");
            return id != null && id.isTextual() && id.textValue().length() <= 128 ? id.textValue() : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
