package com.linearlite.server.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentSessionSseBroadcasterTest {
    @Test
    void channelIsScopedOnlyByExecutionAndDoesNotRequireJobHistory() {
        AgentSessionSseBroadcaster broadcaster = new AgentSessionSseBroadcaster(Duration.ofMinutes(1));

        broadcaster.register("exec-1");
        broadcaster.register("exec-1");
        broadcaster.register("exec-2");

        assertEquals(2, broadcaster.subscriberCount("exec-1"));
        assertEquals(1, broadcaster.subscriberCount("exec-2"));
    }
}
