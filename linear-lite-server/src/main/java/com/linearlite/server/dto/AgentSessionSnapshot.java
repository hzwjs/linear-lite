package com.linearlite.server.dto;

import java.util.List;

/** 完整历史快照只能来自 Pi get_entries 的当前活动分支。 */
public record AgentSessionSnapshot(
        String executionId,
        String piSessionId,
        String leafId,
        List<SessionDisplayBlock> blocks) {
}
