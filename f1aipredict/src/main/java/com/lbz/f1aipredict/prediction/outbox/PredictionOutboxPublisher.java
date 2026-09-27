package com.lbz.f1aipredict.prediction.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** 仅在配置显式开启后运行；所有网络发布均发生在创建事务之外。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "f1.prediction.outbox", name = "enabled", havingValue = "true")
public class PredictionOutboxPublisher {
    private final PredictionRequestOutboxMapper mapper;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitProperties rabbitProperties;
    private final String exchange;
    private final String routingKey;
    private final int batchSize;
    private final int leaseSeconds;
    private final int confirmSeconds;

    public PredictionOutboxPublisher(PredictionRequestOutboxMapper mapper, RabbitTemplate rabbitTemplate,
                                     RabbitProperties rabbitProperties,
                                     @Value("${f1.prediction.outbox.exchange:f1.prediction.request.v2}") String exchange,
                                     @Value("${f1.prediction.outbox.routing-key:prediction.request.v2}") String routingKey,
                                     @Value("${f1.prediction.outbox.batch-size:20}") int batchSize,
                                     @Value("${f1.prediction.outbox.lease-seconds:60}") int leaseSeconds,
                                     @Value("${f1.prediction.outbox.confirm-seconds:10}") int confirmSeconds) {
        this.mapper = mapper;
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitProperties = rabbitProperties;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.confirmSeconds = confirmSeconds;
    }

    /** 在启用发布时验证 confirm/return 机制，防止无确认误标 SENT。 */
    @PostConstruct
    public void validateConfiguration() {
        if (rabbitProperties.getPublisherConfirmType() != CachingConnectionFactory.ConfirmType.CORRELATED
                || !rabbitProperties.isPublisherReturns()
                || !PredictionRequestTopology.REQUEST_EXCHANGE.equals(exchange)
                || !PredictionRequestTopology.REQUEST_KEY.equals(routingKey)
                || batchSize < 1 || batchSize > 100 || leaseSeconds < 2
                || confirmSeconds < 1 || confirmSeconds >= leaseSeconds) {
            throw new IllegalStateException("Prediction outbox publisher configuration is incomplete");
        }
        rabbitTemplate.setMandatory(true);
    }

    /** 到期租约可重新领取，发布失败超过阈值也不丢弃记录。 */
    @Scheduled(fixedDelayString = "${f1.prediction.outbox.scan-delay-ms:5000}")
    public void publishDue() {
        List<Long> ids = mapper.selectDueIds(batchSize);
        for (Long id : ids) {
            String token = UUID.randomUUID().toString();
            if (mapper.claim(id, token, leaseSeconds) != 1) {
                continue;
            }
            PredictionRequestOutbox outbox = mapper.selectById(id);
            if (outbox == null || !token.equals(outbox.getLeaseToken())) {
                continue;
            }
            try {
                publish(outbox);
                if (mapper.markSent(id, token) != 1) {
                    log.warn("预测消息确认后租约已失效: outboxId={}", id);
                }
            } catch (Exception failure) {
                int delay = retryDelay(outbox.getAttempts());
                try {
                    mapper.retryLater(id, token, delay, failure.getClass().getSimpleName());
                } catch (RuntimeException databaseFailure) {
                    // 更新失败则等待租约过期后重新领取，绝不因数据库故障伪造 SENT。
                    log.warn("预测消息重试状态未能落库: outboxId={}", id);
                }
                log.warn("预测消息发布未获路由确认: outboxId={}, errorType={}",
                        id, failure.getClass().getSimpleName());
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void publish(PredictionRequestOutbox outbox) throws Exception {
        CorrelationData correlation = new CorrelationData(outbox.getMessageId());
        rabbitTemplate.convertAndSend(exchange, routingKey,
                outbox.getPayloadJson().getBytes(StandardCharsets.UTF_8), message -> {
                    message.getMessageProperties().setMessageId(outbox.getMessageId());
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setContentEncoding("UTF-8");
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                }, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(confirmSeconds, TimeUnit.SECONDS);
        if (!confirm.isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException("Prediction message was not confirmed and routed");
        }
    }

    private static int retryDelay(Integer attempts) {
        int exponent = Math.min(8, Math.max(0, attempts == null ? 0 : attempts - 1));
        int base = Math.min(300, 2 << exponent);
        return Math.min(360, base + ThreadLocalRandom.current().nextInt(Math.max(1, base / 5)));
    }
}
