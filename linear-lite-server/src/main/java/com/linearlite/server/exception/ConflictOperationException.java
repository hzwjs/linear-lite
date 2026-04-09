package com.linearlite.server.exception;

/**
 * 与当前资源状态冲突（如超过允许删除时间窗口），映射为 HTTP 409。
 */
public class ConflictOperationException extends RuntimeException {

    public ConflictOperationException(String message) {
        super(message);
    }
}
