package com.linearlite.server.exception;

/**
 * 资源不存在时抛出，由 GlobalExceptionHandler 映射为 404。
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
