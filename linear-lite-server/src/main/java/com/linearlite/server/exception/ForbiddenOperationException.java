package com.linearlite.server.exception;

/**
 * 当前用户无权执行该操作。
 */
public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}
