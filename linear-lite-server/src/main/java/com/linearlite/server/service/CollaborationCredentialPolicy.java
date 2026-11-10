package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;

/** 排除本次请求携带的执行凭据，避免调用方将凭据粘贴进业务材料。 */
public final class CollaborationCredentialPolicy {
    private CollaborationCredentialPolicy() { }

    public static void requireSafe(JsonNode payload, String... knownCredentials) {
        if (payload == null) return;
        if (payload.isTextual()) {
            requireSafe(payload.textValue(), knownCredentials);
        } else if (payload.isContainerNode()) {
            payload.elements().forEachRemaining(node -> requireSafe(node, knownCredentials));
        }
    }

    public static void requireSafe(String text, String... knownCredentials) {
        if (text == null) return;
        for (String credential : knownCredentials) {
            if (credential != null && !credential.isEmpty() && text.contains(credential)) {
                throw new IllegalArgumentException("协作业务材料包含执行凭据");
            }
        }
    }
}
