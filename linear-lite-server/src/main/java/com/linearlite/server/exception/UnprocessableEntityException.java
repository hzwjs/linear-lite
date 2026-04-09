package com.linearlite.server.exception;

/**
 * 语义上无法处理的内容（如 @ 了非项目成员），映射为 HTTP 422。
 */
public class UnprocessableEntityException extends RuntimeException {

    public UnprocessableEntityException(String message) {
        super(message);
    }
}
