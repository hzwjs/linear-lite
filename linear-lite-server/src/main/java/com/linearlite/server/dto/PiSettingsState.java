package com.linearlite.server.dto;

import java.util.List;

/** 当前 Pi runtime 的设置快照；只用于本次 SSE 推送，不在服务端保存副本。 */
public record PiSettingsState(
        String executionId,
        PiSettingsCurrent current,
        List<PiModelDescriptor> models,
        List<String> thinkingLevels) {
}
