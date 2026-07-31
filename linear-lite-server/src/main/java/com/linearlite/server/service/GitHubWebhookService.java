package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.ProjectGitHubRepository;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskComment;
import com.linearlite.server.exception.UnauthorizedException;
import com.linearlite.server.mapper.TaskCommentMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.util.WebhookSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub Push Webhook：校验 HMAC 后按固定仓库身份同步任务评论。 */
@Service
public class GitHubWebhookService {
    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookService.class);
    private static final Pattern TASK_KEY_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9]{0,15}-\\d{1,9}\\b");
    private static final String SOURCE_GITHUB_COMMIT = "github_commit";
    private final GitHubProjectRepositoryService repositoryService;
    private final TaskMapper taskMapper;
    private final TaskCommentMapper taskCommentMapper;
    private final ObjectMapper objectMapper;
    private final WebhookSecretCipher secretCipher;

    public GitHubWebhookService(GitHubProjectRepositoryService repositoryService, TaskMapper taskMapper,
                                TaskCommentMapper taskCommentMapper, ObjectMapper objectMapper, WebhookSecretCipher secretCipher) {
        this.repositoryService = repositoryService; this.taskMapper = taskMapper; this.taskCommentMapper = taskCommentMapper; this.objectMapper = objectMapper; this.secretCipher = secretCipher;
    }

    @Transactional(rollbackFor = Exception.class)
    public void handlePush(String eventName, String signature, String rawBody) {
        if (!"push".equals(eventName)) return;
        GitHubPushEvent event = parse(rawBody);
        if (event == null || event.repository() == null || event.repository().htmlUrl() == null) return;
        GitHubProjectRepositoryService.RepositoryIdentity identity;
        try { identity = GitHubProjectRepositoryService.normalizeRepositoryUrl(event.repository().htmlUrl()); }
        catch (IllegalArgumentException ignored) { return; }
        ProjectGitHubRepository repository = repositoryService.listAll().stream()
                .filter(r -> r.getRepositoryUrl().equals(identity.url()) && r.getRepositoryPath().equals(event.repository().fullName()))
                .findFirst().orElse(null);
        if (repository == null || !validSignature(signature, rawBody, secretCipher.decrypt(repository.getWebhookSecret()))) {
            throw new UnauthorizedException("GitHub Webhook 签名无效");
        }
        if (event.commits() == null) return;
        for (GitHubCommit commit : event.commits()) {
            if (commit == null || blank(commit.id()) || commit.id().length() > 64) continue;
            for (String taskKey : extractTaskKeys(commit.message())) createComment(repository, taskKey, commit, shortBranch(event.ref()));
        }
    }

    private void createComment(ProjectGitHubRepository repository, String taskKey, GitHubCommit commit, String branch) {
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>().eq(Task::getTaskKey, taskKey));
        if (task == null || !repository.getProjectId().equals(task.getProjectId())) return;
        String externalRef = repository.getId() + ":" + commit.id();
        Long existing = taskCommentMapper.selectCount(new LambdaQueryWrapper<TaskComment>().eq(TaskComment::getSourceType, SOURCE_GITHUB_COMMIT).eq(TaskComment::getExternalRef, externalRef).eq(TaskComment::getTaskId, task.getId()));
        if (existing != null && existing > 0) return;
        TaskComment comment = new TaskComment(); comment.setTaskId(task.getId()); comment.setAuthorId(repository.getCreatedBy());
        String summary = blank(commit.message()) ? "" : firstLine(commit.message()); String shortSha = commit.id().length() > 8 ? commit.id().substring(0, 8) : commit.id();
        String commitLine = blank(commit.url()) ? shortSha + " " + summary : "[" + shortSha + "](" + commit.url() + ") " + summary;
        comment.setBody("**GitHub 提交**\n\n" + commitLine + (branch.isBlank() ? "" : "\n\n分支：" + branch));
        comment.setDepth(0); comment.setSourceType(SOURCE_GITHUB_COMMIT); comment.setExternalRef(externalRef); comment.setCreatedAt(LocalDateTime.now()); taskCommentMapper.insert(comment);
    }

    private GitHubPushEvent parse(String body) { if (blank(body)) return null; try { return objectMapper.readValue(body, GitHubPushEvent.class); } catch (JsonProcessingException e) { log.warn("GitHub Webhook 请求体无法解析，已忽略"); return null; } }
    private static boolean validSignature(String signature, String body, String secret) {
        if (blank(signature) || blank(secret) || !signature.startsWith("sha256=")) return false;
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + hex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) { return false; }
    }
    private static String hex(byte[] bytes) { StringBuilder b = new StringBuilder(bytes.length * 2); for (byte v : bytes) b.append(String.format("%02x", v)); return b.toString(); }
    private static Set<String> extractTaskKeys(String... texts) { Set<String> keys = new LinkedHashSet<>(); for (String text : texts) { if (!blank(text)) { Matcher m = TASK_KEY_PATTERN.matcher(text); while (m.find()) keys.add(m.group()); } } return keys; }
    private static String shortBranch(String ref) { return ref != null && ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ""; }
    private static String firstLine(String v) { int n = v.indexOf('\n'); return (n < 0 ? v : v.substring(0, n)).trim(); }
    private static boolean blank(String v) { return v == null || v.isBlank(); }

    public record GitHubPushEvent(String ref, GitHubRepository repository, List<GitHubCommit> commits) {}
    public record GitHubRepository(
            @JsonProperty("full_name") String fullName,
            @JsonProperty("html_url") String htmlUrl) {}
    public record GitHubCommit(String id, String message, String url) {}
}
