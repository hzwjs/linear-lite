package com.linearlite.server.controller;

import com.linearlite.server.service.GitLabWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** GitLab 的公开回调入口；认证只使用 X-Gitlab-Token。 */
@RestController
public class GitLabWebhookController {

    private final GitLabWebhookService webhookService;

    public GitLabWebhookController(GitLabWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/api/webhooks/gitlab")
    public ResponseEntity<Void> receive(HttpServletRequest request, @RequestBody String rawBody) {
        webhookService.handlePush(request.getHeader("X-Gitlab-Token"), rawBody);
        return ResponseEntity.ok().build();
    }
}
