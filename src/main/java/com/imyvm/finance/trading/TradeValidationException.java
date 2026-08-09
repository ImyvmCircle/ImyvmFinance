package com.imyvm.finance.trading;

public final class TradeValidationException extends Exception {
    private final String messageKey;
    private final Object[] messageArguments;

    public TradeValidationException(String messageKey, Object... messageArguments) {
        this.messageKey = messageKey;
        this.messageArguments = messageArguments;
    }

    public String messageKey() {
        return messageKey;
    }

    public Object[] messageArguments() {
        return messageArguments;
    }
}
