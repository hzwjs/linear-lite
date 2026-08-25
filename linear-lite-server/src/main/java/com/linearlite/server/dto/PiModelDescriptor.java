package com.linearlite.server.dto;

/** Pi 返回的可选模型，展示名称由 Bridge 在 Pi 边界确定。 */
public record PiModelDescriptor(String provider, String modelId, String label) {
}
