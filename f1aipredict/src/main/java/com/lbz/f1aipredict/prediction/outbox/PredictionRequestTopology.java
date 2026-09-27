package com.lbz.f1aipredict.prediction.outbox;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 与 Python 消费端一致的持久请求队列和死信拓扑。 */
@Configuration
@ConditionalOnProperty(prefix = "f1.prediction.outbox", name = "enabled", havingValue = "true")
public class PredictionRequestTopology {
    public static final String REQUEST_EXCHANGE = "f1.prediction.request.v2";
    public static final String REQUEST_QUEUE = "f1.prediction.request.v2";
    public static final String REQUEST_KEY = "prediction.request.v2";
    public static final String DEAD_EXCHANGE = "f1.prediction.dead.v2";
    public static final String DEAD_QUEUE = "f1.prediction.dead.v2";
    public static final String DEAD_KEY = "prediction.dead.v2";

    @Bean
    public DirectExchange predictionRequestExchange() {
        return new DirectExchange(REQUEST_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange predictionDeadExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue predictionRequestQueue() {
        return QueueBuilder.durable(REQUEST_QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey(DEAD_KEY)
                .build();
    }

    @Bean
    public Queue predictionDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding predictionRequestBinding() {
        return BindingBuilder.bind(predictionRequestQueue()).to(predictionRequestExchange())
                .with(REQUEST_KEY);
    }

    @Bean
    public Binding predictionDeadBinding() {
        return BindingBuilder.bind(predictionDeadQueue()).to(predictionDeadExchange())
                .with(DEAD_KEY);
    }
}
