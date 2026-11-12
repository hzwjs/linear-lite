package com.linearlite.server.dto;

import java.time.Instant;

public record HostDeviceAuthorizationResponse(Long id, Long userId, String deviceId, String deviceName,
                                              boolean authorized, boolean online,
                                              Instant lastHeartbeatAt, Instant createdAt) {}
