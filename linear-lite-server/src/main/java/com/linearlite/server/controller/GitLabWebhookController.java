package com.linearlite.server.controller;

import com.linearlite.server.service.GitLabWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitLab 入站 Webhook。无需 JWT（由 JwtAuthFilter 放行），改用 X-Gitlab-Token 校验；
 * token 无效返回 401，解析失败或无关事件返回 200 避免 GitLab 重试。
 */
@RestController
public class GitLabWebhookController {

    private static final String GITLAB_TOKEN_HEADER = "X-Gitlab-Token";

    private final GitLabWebhookService webhookService;

    public GitLabWebhookController(GitLabWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/api/webhooks/gitlab")
    public ResponseEntity<Void> gitlab(HttpServletRequest request, @RequestBody String rawBody) {
        webhookService.handlePush(request.getHeader(GITLAB_TOKEN_HEADER), rawBody);
        return ResponseEntity.ok().build();
    }
}
