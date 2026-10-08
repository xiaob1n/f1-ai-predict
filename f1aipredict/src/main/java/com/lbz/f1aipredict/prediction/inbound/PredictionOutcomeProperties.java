package com.lbz.f1aipredict.prediction.inbound;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 预测终态消费设置；默认关闭以避免未配置时连接真实 Broker。 */
@ConfigurationProperties(prefix = "f1predict.prediction.outcome")
public class PredictionOutcomeProperties {

    private boolean enabled;
    private String resultExchange = "f1.prediction.result.v2";
    private String resultQueue = "f1.prediction.result.v2";
    private String resultRoutingKey = "f1.prediction.result.v2";
    private String resultDeadLetterExchange = "f1.prediction.result.dead.v2";
    private String resultDeadLetterQueue = "f1.prediction.result.dead.v2";
    private String resultDeadLetterRoutingKey = "f1.prediction.result.dead.v2";
    private String failureExchange = "f1.prediction.failure.v2";
    private String failureQueue = "f1.prediction.failure.v2";
    private String failureRoutingKey = "f1.prediction.failure.v2";
    private String failureDeadLetterExchange = "f1.prediction.failure.dead.v2";
    private String failureDeadLetterQueue = "f1.prediction.failure.dead.v2";
    private String failureDeadLetterRoutingKey = "f1.prediction.failure.dead.v2";
    private int resultMaxMessageBytes = 262144;
    private int failureMaxMessageBytes = 16384;
    private int quarantineMaxBodyBytes = 1048576;
    private int prefetch = 10;
    private int retryBackoffMaxSeconds = 30;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getResultExchange() { return resultExchange; }
    public void setResultExchange(String resultExchange) { this.resultExchange = resultExchange; }
    public String getResultQueue() { return resultQueue; }
    public void setResultQueue(String resultQueue) { this.resultQueue = resultQueue; }
    public String getResultRoutingKey() { return resultRoutingKey; }
    public void setResultRoutingKey(String resultRoutingKey) { this.resultRoutingKey = resultRoutingKey; }
    public String getResultDeadLetterExchange() { return resultDeadLetterExchange; }
    public void setResultDeadLetterExchange(String value) { this.resultDeadLetterExchange = value; }
    public String getResultDeadLetterQueue() { return resultDeadLetterQueue; }
    public void setResultDeadLetterQueue(String value) { this.resultDeadLetterQueue = value; }
    public String getResultDeadLetterRoutingKey() { return resultDeadLetterRoutingKey; }
    public void setResultDeadLetterRoutingKey(String value) { this.resultDeadLetterRoutingKey = value; }
    public String getFailureExchange() { return failureExchange; }
    public void setFailureExchange(String failureExchange) { this.failureExchange = failureExchange; }
    public String getFailureQueue() { return failureQueue; }
    public void setFailureQueue(String failureQueue) { this.failureQueue = failureQueue; }
    public String getFailureRoutingKey() { return failureRoutingKey; }
    public void setFailureRoutingKey(String failureRoutingKey) { this.failureRoutingKey = failureRoutingKey; }
    public String getFailureDeadLetterExchange() { return failureDeadLetterExchange; }
    public void setFailureDeadLetterExchange(String value) { this.failureDeadLetterExchange = value; }
    public String getFailureDeadLetterQueue() { return failureDeadLetterQueue; }
    public void setFailureDeadLetterQueue(String value) { this.failureDeadLetterQueue = value; }
    public String getFailureDeadLetterRoutingKey() { return failureDeadLetterRoutingKey; }
    public void setFailureDeadLetterRoutingKey(String value) { this.failureDeadLetterRoutingKey = value; }
    public int getResultMaxMessageBytes() { return resultMaxMessageBytes; }
    public void setResultMaxMessageBytes(int value) { this.resultMaxMessageBytes = value; }
    public int getFailureMaxMessageBytes() { return failureMaxMessageBytes; }
    public void setFailureMaxMessageBytes(int value) { this.failureMaxMessageBytes = value; }
    public int getQuarantineMaxBodyBytes() { return quarantineMaxBodyBytes; }
    public void setQuarantineMaxBodyBytes(int value) { this.quarantineMaxBodyBytes = value; }
    public int getPrefetch() { return prefetch; }
    public void setPrefetch(int value) { this.prefetch = value; }
    public int getRetryBackoffMaxSeconds() { return retryBackoffMaxSeconds; }
    public void setRetryBackoffMaxSeconds(int value) { this.retryBackoffMaxSeconds = value; }
}
