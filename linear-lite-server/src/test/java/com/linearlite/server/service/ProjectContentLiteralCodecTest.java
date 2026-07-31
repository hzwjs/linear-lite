package com.linearlite.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectContentLiteralCodecTest {
    private final ProjectContentLiteralCodec codec = new ProjectContentLiteralCodec();

    @Test
    void encodesEveryUnicodeCodePointAsOneWhitespaceToken() {
        assertEquals("u6d3e u5355 u20 u1f680", codec.encode("派单 🚀"));
    }

    @Test
    void usesTheSameRootLowercaseRuleAsPreviousContainsSearch() {
        assertEquals("u61 u62 u63", codec.encode("AbC"));
        assertEquals("", codec.encode(null));
    }
}
