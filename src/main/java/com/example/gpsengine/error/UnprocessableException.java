package com.example.gpsengine.error;

public class UnprocessableException extends RuntimeException {
    private final String errorCode;

    public UnprocessableException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

