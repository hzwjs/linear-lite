package com.linearlite.server.controller;

import com.linearlite.server.service.GitHubWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** GitHub 的公开回调入口；认证使用 X-Hub-Signature-256。 */
@RestController
public class GitHubWebhookController {
    private final GitHubWebhookService webhookService;
    public GitHubWebhookController(GitHubWebhookService webhookService) { this.webhookService = webhookService; }
    @PostMapping("/api/webhooks/github")
    public ResponseEntity<Void> receive(HttpServletRequest request, @RequestBody String rawBody) {
        webhookService.handlePush(request.getHeader("X-GitHub-Event"), request.getHeader("X-Hub-Signature-256"), rawBody);
        return ResponseEntity.ok().build();
    }
}
