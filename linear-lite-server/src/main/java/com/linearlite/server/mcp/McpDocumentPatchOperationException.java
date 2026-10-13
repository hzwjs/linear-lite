package com.linearlite.server.mcp;

public class McpDocumentPatchOperationException extends RuntimeException {
    private final int operationIndex;

    public McpDocumentPatchOperationException(int operationIndex, String message, Throwable cause) {
        super(message, cause);
        this.operationIndex = operationIndex;
    }

    public int getOperationIndex() {
        return operationIndex;
    }
}
