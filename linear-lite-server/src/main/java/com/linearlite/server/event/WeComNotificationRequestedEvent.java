package com.linearlite.server.event;

public record WeComNotificationRequestedEvent(
        Long localUserId,
        String taskKey,
        String summary) {
}
