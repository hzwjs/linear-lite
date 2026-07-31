package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.GitHubRepositoryResponse;
import com.linearlite.server.entity.ProjectGitHubRepository;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectGitHubRepositoryMapper;
import com.linearlite.server.util.WebhookSecretCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/** 项目维度的 GitHub 仓库配置；一个项目可注册任意多个仓库。 */
@Service
public class GitHubProjectRepositoryService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ProjectAccessGuard projectAccessGuard;
    private final ProjectGitHubRepositoryMapper repositoryMapper;
    private final WebhookSecretCipher secretCipher;

    public GitHubProjectRepositoryService(ProjectAccessGuard projectAccessGuard, ProjectGitHubRepositoryMapper repositoryMapper, WebhookSecretCipher secretCipher) {
        this.projectAccessGuard = projectAccessGuard;
        this.repositoryMapper = repositoryMapper;
        this.secretCipher = secretCipher;
    }

    public List<GitHubRepositoryResponse> list(Long projectId, Long userId) {
        projectAccessGuard.requireOwner(projectId, userId);
        return repositoryMapper.selectList(new LambdaQueryWrapper<ProjectGitHubRepository>()
                .eq(ProjectGitHubRepository::getProjectId, projectId)
                .orderByAsc(ProjectGitHubRepository::getCreatedAt, ProjectGitHubRepository::getId))
                .stream().map(r -> response(r, null)).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public GitHubRepositoryResponse create(Long projectId, Long userId, String rawUrl) {
        projectAccessGuard.requireOwner(projectId, userId);
        RepositoryIdentity identity = normalizeRepositoryUrl(rawUrl);
        Long existing = repositoryMapper.selectCount(new LambdaQueryWrapper<ProjectGitHubRepository>()
                .eq(ProjectGitHubRepository::getRepositoryUrl, identity.url()));
        if (existing != null && existing > 0) throw new ConflictOperationException("该 GitHub 仓库已被配置到其他项目");
        String secret = randomSecret();
        ProjectGitHubRepository r = new ProjectGitHubRepository();
        r.setProjectId(projectId); r.setRepositoryUrl(identity.url()); r.setRepositoryPath(identity.path());
        r.setWebhookSecret(secretCipher.encrypt(secret)); r.setCreatedBy(userId); r.setCreatedAt(LocalDateTime.now()); r.setUpdatedAt(LocalDateTime.now());
        repositoryMapper.insert(r);
        return response(r, secret);
    }

    @Transactional(rollbackFor = Exception.class)
    public GitHubRepositoryResponse resetWebhookSecret(Long projectId, Long repositoryId, Long userId) {
        ProjectGitHubRepository r = requireOwned(projectId, repositoryId, userId);
        String secret = randomSecret(); r.setWebhookSecret(secretCipher.encrypt(secret)); r.setUpdatedAt(LocalDateTime.now()); repositoryMapper.updateById(r);
        return response(r, secret);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long projectId, Long repositoryId, Long userId) {
        ProjectGitHubRepository r = requireOwned(projectId, repositoryId, userId); repositoryMapper.deleteById(r.getId());
    }

    public ProjectGitHubRepository resolve(Long repositoryId) { return repositoryMapper.selectById(repositoryId); }

    public List<ProjectGitHubRepository> listAll() { return repositoryMapper.selectList(null); }

    public static RepositoryIdentity normalizeRepositoryUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalArgumentException("GitHub 仓库地址不能为空");
        try {
            URI uri = new URI(rawUrl.trim()); String scheme = uri.getScheme(); String host = uri.getHost(); String path = uri.getPath();
            if ((!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) || host == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || path == null || path.isBlank() || "/".equals(path))
                throw new IllegalArgumentException("请输入 GitHub 项目的 Web URL");
            String repositoryPath = path.replaceAll("^/+|/+$", "");
            if (repositoryPath.isBlank() || repositoryPath.endsWith(".git") || repositoryPath.split("/").length != 2)
                throw new IllegalArgumentException("请输入 GitHub 项目的 Web URL，不能使用 clone URL");
            return new RepositoryIdentity(scheme.toLowerCase() + "://" + host.toLowerCase()
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + "/" + repositoryPath, repositoryPath);
        } catch (URISyntaxException e) { throw new IllegalArgumentException("请输入有效的 GitHub 项目 Web URL"); }
    }

    private ProjectGitHubRepository requireOwned(Long projectId, Long repositoryId, Long userId) {
        projectAccessGuard.requireOwner(projectId, userId); ProjectGitHubRepository r = repositoryMapper.selectById(repositoryId);
        if (r == null || !projectId.equals(r.getProjectId())) throw new ResourceNotFoundException("GitHub 仓库配置不存在"); return r;
    }
    private static GitHubRepositoryResponse response(ProjectGitHubRepository r, String secret) {
        return new GitHubRepositoryResponse(r.getId(), r.getRepositoryUrl(), r.getRepositoryPath(), secret, r.getCreatedAt());
    }
    private static String randomSecret() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public record RepositoryIdentity(String url, String path) {}
}
