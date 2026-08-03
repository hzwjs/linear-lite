package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubWebhookServiceTest {

    @Test
    void parsesGitHubSnakeCaseRepositoryIdentity() throws Exception {
        String payload = "{\"ref\":\"refs/heads/main\",\"repository\":{"
                + "\"full_name\":\"hzwjs/linear-lite\","
                + "\"html_url\":\"https://github.com/hzwjs/linear-lite\"},"
                + "\"commits\":[]}";

        GitHubWebhookService.GitHubPushEvent event = new ObjectMapper()
                .readValue(payload, GitHubWebhookService.GitHubPushEvent.class);

        assertEquals("hzwjs/linear-lite", event.repository().fullName());
        assertEquals("https://github.com/hzwjs/linear-lite", event.repository().htmlUrl());
    }

    @Test
    void extractsCompoundLinearLiteTaskKey() {
        assertTrue(GitHubWebhookService.extractTaskKeys(
                "feat(settings): LINEAR-LITE-57 项目设置改为独立配置页").contains("LINEAR-LITE-57"));
    }
}
