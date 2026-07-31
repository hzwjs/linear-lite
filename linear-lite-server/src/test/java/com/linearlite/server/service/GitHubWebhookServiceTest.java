package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
