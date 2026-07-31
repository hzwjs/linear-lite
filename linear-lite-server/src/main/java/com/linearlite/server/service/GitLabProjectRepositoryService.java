package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.GitLabRepositoryResponse;
import com.linearlite.server.entity.ProjectGitLabRepository;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectGitLabRepositoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/** 项目维度的 GitLab 仓库配置；一个项目可注册任意多个仓库。 */
@Service
public class GitLabProjectRepositoryService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProjectAccessGuard projectAccessGuard;
    private final ProjectGitLabRepositoryMapper repositoryMapper;

    public GitLabProjectRepositoryService(ProjectAccessGuard projectAccessGuard,
                                          ProjectGitLabRepositoryMapper repositoryMapper) {
        this.projectAccessGuard = projectAccessGuard;
        this.repositoryMapper = repositoryMapper;
    }

    public List<GitLabRepositoryResponse> list(Long projectId, Long userId) {
        projectAccessGuard.requireOwner(projectId, userId);
        return repositoryMapper.selectList(new LambdaQueryWrapper<ProjectGitLabRepository>()
                        .eq(ProjectGitLabRepository::getProjectId, projectId)
                        .orderByAsc(ProjectGitLabRepository::getCreatedAt, ProjectGitLabRepository::getId))
                .stream().map(repository -> response(repository, null)).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public GitLabRepositoryResponse create(Long projectId, Long userId, String rawRepositoryUrl) {
        projectAccessGuard.requireOwner(projectId, userId);
        RepositoryIdentity identity = normalizeRepositoryUrl(rawRepositoryUrl);
        Long existing = repositoryMapper.selectCount(new LambdaQueryWrapper<ProjectGitLabRepository>()
                .eq(ProjectGitLabRepository::getRepositoryUrl, identity.url()));
        if (existing != null && existing > 0) {
            throw new ConflictOperationException("该 GitLab 仓库已被配置到其他项目");
        }
        String webhookToken = randomSecret();
        ProjectGitLabRepository repository = new ProjectGitLabRepository();
        repository.setProjectId(projectId);
        repository.setRepositoryUrl(identity.url());
        repository.setRepositoryPath(identity.path());
        repository.setWebhookTokenHash(hash(webhookToken));
        repository.setCreatedBy(userId);
        repository.setCreatedAt(LocalDateTime.now());
        repository.setUpdatedAt(LocalDateTime.now());
        repositoryMapper.insert(repository);
        return response(repository, webhookToken);
    }

    @Transactional(rollbackFor = Exception.class)
    public GitLabRepositoryResponse resetWebhookToken(Long projectId, Long repositoryId, Long userId) {
        ProjectGitLabRepository repository = requireOwnedRepository(projectId, repositoryId, userId);
        String webhookToken = randomSecret();
        repository.setWebhookTokenHash(hash(webhookToken));
        repository.setUpdatedAt(LocalDateTime.now());
        repositoryMapper.updateById(repository);
        return response(repository, webhookToken);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long projectId, Long repositoryId, Long userId) {
        ProjectGitLabRepository repository = requireOwnedRepository(projectId, repositoryId, userId);
        repositoryMapper.deleteById(repository.getId());
    }

    /** Webhook 只通过哈希定位并再以常量时间确认 token，明文不会落库或回读。 */
    public ProjectGitLabRepository resolveWebhookToken(String webhookToken) {
        if (webhookToken == null || webhookToken.isBlank()) {
            return null;
        }
        String tokenHash = hash(webhookToken);
        ProjectGitLabRepository repository = repositoryMapper.selectOne(
                new LambdaQueryWrapper<ProjectGitLabRepository>()
                        .eq(ProjectGitLabRepository::getWebhookTokenHash, tokenHash));
        if (repository == null || !MessageDigest.isEqual(
                tokenHash.getBytes(StandardCharsets.UTF_8),
                repository.getWebhookTokenHash().getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return repository;
    }

    private ProjectGitLabRepository requireOwnedRepository(Long projectId, Long repositoryId, Long userId) {
        projectAccessGuard.requireOwner(projectId, userId);
        ProjectGitLabRepository repository = repositoryMapper.selectById(repositoryId);
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ResourceNotFoundException("GitLab 仓库配置不存在");
        }
        return repository;
    }

    private static GitLabRepositoryResponse response(ProjectGitLabRepository repository, String webhookToken) {
        return new GitLabRepositoryResponse(repository.getId(), repository.getRepositoryUrl(),
                repository.getRepositoryPath(), webhookToken, repository.getCreatedAt());
    }

    /** 仓库 URL 是唯一身份，Webhook 事件必须以同样规则规范化后完全相等。 */
    public static RepositoryIdentity normalizeRepositoryUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("GitLab 仓库地址不能为空");
        }
        try {
            URI uri = new URI(rawUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || path == null || path.isBlank() || "/".equals(path)) {
                throw new IllegalArgumentException("请输入 GitLab 项目的 Web URL");
            }
            String repositoryPath = path.replaceAll("^/+|/+$", "");
            if (repositoryPath.isBlank() || repositoryPath.endsWith(".git")) {
                throw new IllegalArgumentException("请输入 GitLab 项目的 Web URL，不能使用 clone URL");
            }
            String normalizedUrl = scheme.toLowerCase() + "://" + host.toLowerCase()
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + "/" + repositoryPath;
            if (normalizedUrl.length() > 512 || repositoryPath.length() > 512) {
                throw new IllegalArgumentException("GitLab 仓库地址过长");
            }
            return new RepositoryIdentity(normalizedUrl, repositoryPath);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("请输入有效的 GitLab 项目 Web URL");
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("GitLab Webhook Token 哈希失败", e);
        }
    }

    public record RepositoryIdentity(String url, String path) {
    }
}
