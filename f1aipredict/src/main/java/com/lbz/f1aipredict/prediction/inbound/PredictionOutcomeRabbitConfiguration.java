package com.lbz.f1aipredict.prediction.inbound;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 只在显式启用时装配终态队列、持久拓扑及手动确认监听器。 */
@Configuration
@EnableRabbit
@EnableConfigurationProperties(PredictionOutcomeProperties.class)
@ConditionalOnProperty(prefix = "f1predict.prediction.outcome", name = "enabled", havingValue = "true")
public class PredictionOutcomeRabbitConfiguration {

    @Bean
    public DirectExchange resultExchange(PredictionOutcomeProperties properties) {
        return new DirectExchange(properties.getResultExchange(), true, false);
    }

    @Bean
    public DirectExchange resultDeadLetterExchange(PredictionOutcomeProperties properties) {
        return new DirectExchange(properties.getResultDeadLetterExchange(), true, false);
    }

    @Bean
    public Queue resultDeadLetterQueue(PredictionOutcomeProperties properties) {
        return QueueBuilder.durable(properties.getResultDeadLetterQueue()).build();
    }

    @Bean
    public Queue resultQueue(PredictionOutcomeProperties properties) {
        return QueueBuilder.durable(properties.getResultQueue())
                .deadLetterExchange(properties.getResultDeadLetterExchange())
                .deadLetterRoutingKey(properties.getResultDeadLetterRoutingKey()).build();
    }

    @Bean
    public Binding resultBinding(PredictionOutcomeProperties properties) {
        return BindingBuilder.bind(resultQueue(properties)).to(resultExchange(properties))
                .with(properties.getResultRoutingKey());
    }

    @Bean
    public Binding resultDeadLetterBinding(PredictionOutcomeProperties properties) {
        return BindingBuilder.bind(resultDeadLetterQueue(properties)).to(resultDeadLetterExchange(properties))
                .with(properties.getResultDeadLetterRoutingKey());
    }

    @Bean
    public DirectExchange failureExchange(PredictionOutcomeProperties properties) {
        return new DirectExchange(properties.getFailureExchange(), true, false);
    }

    @Bean
    public DirectExchange failureDeadLetterExchange(PredictionOutcomeProperties properties) {
        return new DirectExchange(properties.getFailureDeadLetterExchange(), true, false);
    }

    @Bean
    public Queue failureDeadLetterQueue(PredictionOutcomeProperties properties) {
        return QueueBuilder.durable(properties.getFailureDeadLetterQueue()).build();
    }

    @Bean
    public Queue failureQueue(PredictionOutcomeProperties properties) {
        return QueueBuilder.durable(properties.getFailureQueue())
                .deadLetterExchange(properties.getFailureDeadLetterExchange())
                .deadLetterRoutingKey(properties.getFailureDeadLetterRoutingKey()).build();
    }

    @Bean
    public Binding failureBinding(PredictionOutcomeProperties properties) {
        return BindingBuilder.bind(failureQueue(properties)).to(failureExchange(properties))
                .with(properties.getFailureRoutingKey());
    }

    @Bean
    public Binding failureDeadLetterBinding(PredictionOutcomeProperties properties) {
        return BindingBuilder.bind(failureDeadLetterQueue(properties)).to(failureDeadLetterExchange(properties))
                .with(properties.getFailureDeadLetterRoutingKey());
    }

    @Bean("predictionOutcomeListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory predictionOutcomeListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            PredictionOutcomeProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(Math.max(1, Math.min(100, properties.getPrefetch())));
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        return factory;
    }

    @Bean
    public RabbitAdmin predictionOutcomeRabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }
}
