package com.lbz.f1aipredict.prediction.inbound;

/** 隔离记录未能持久化；监听器不得 ACK 原消息。 */
public class OutcomeQuarantineUnavailableException extends RuntimeException {
    public OutcomeQuarantineUnavailableException(String message) {
        super(message);
    }
}
