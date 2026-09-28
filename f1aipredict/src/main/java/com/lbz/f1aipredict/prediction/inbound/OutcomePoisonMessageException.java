package com.lbz.f1aipredict.prediction.inbound;

/** 永久契约或业务错误；监听器须先持久隔离，成功提交后才确认消息。 */
public class OutcomePoisonMessageException extends RuntimeException {
    private final String reasonCode;

    public OutcomePoisonMessageException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() { return reasonCode; }
}
