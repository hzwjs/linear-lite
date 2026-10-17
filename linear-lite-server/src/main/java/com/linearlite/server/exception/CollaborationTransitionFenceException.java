package com.linearlite.server.exception;

public class CollaborationTransitionFenceException extends RuntimeException {
    private final String errorType;

    public CollaborationTransitionFenceException(String errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public String getErrorType() { return errorType; }
}
