package com.linearlite.server.service;

import com.linearlite.server.dto.ProjectContentSearchResponse;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.ProjectMemberMapper.ProjectPermissionScope;
import com.linearlite.server.mapper.ProjectContentSearchMapper;
import com.linearlite.server.service.ProjectContentSearchIndex.SearchScope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 在用户有权访问的项目中统一检索任务和项目文档。 */
@Service
public class ProjectContentSearchService {
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_RESULTS = 50;
    private static final Pattern TASK_KEY_QUERY = Pattern.compile("^\\S+-\\d+$");
    private static final Pattern TASK_NUMBER_QUERY = Pattern.compile("^\\d+$");
    private static final List<SearchChannel> GLOBAL_CHANNELS =
            List.of(SearchChannel.TITLE, SearchChannel.BODY, SearchChannel.SEMANTIC);

    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectContentSearchMapper projectContentSearchMapper;
    private final ProjectContentSearchIndex searchIndex;

    public ProjectContentSearchService(ProjectMemberMapper projectMemberMapper,
                                       ProjectContentSearchMapper projectContentSearchMapper,
                                       ProjectContentSearchIndex searchIndex) {
        this.projectMemberMapper = projectMemberMapper;
        this.projectContentSearchMapper = projectContentSearchMapper;
        this.searchIndex = searchIndex;
    }

    public List<ProjectContentSearchResponse> search(String query, Long userId) {
        requireUser(userId);
        String normalized = normalizeQuery(query);
        if (normalized.isEmpty()) return List.of();
        String normalizedTaskKey = normalized.toUpperCase(Locale.ROOT);
        // 结构化任务编号直接命中数据库；权限在同一条 SQL 中通过项目成员关系约束。
        if (TASK_KEY_QUERY.matcher(normalizedTaskKey).matches()) {
            return toTaskSearchResults(projectContentSearchMapper.selectTaskSearchByKey(normalizedTaskKey, userId));
        }
        if (TASK_NUMBER_QUERY.matcher(normalized).matches()) {
            return toTaskSearchResults(projectContentSearchMapper.selectTaskSearchByNumber(
                    normalizeTaskNumber(normalized), userId));
        }
        Map<Long, ProjectPermissionScope> scopeByProjectId = permissionScopes(userId);
        if (scopeByProjectId.isEmpty()) return List.of();
        SearchScope searchScope = new SearchScope(List.copyOf(scopeByProjectId.keySet()),
                List.of(ProjectContentType.TASK, ProjectContentType.DOCUMENT));
        return search(normalized, scopeByProjectId, searchScope, GLOBAL_CHANNELS);
    }

    public List<ProjectContentSearchResponse> searchDocuments(Long projectId, String query, Long userId) {
        if (projectId == null) throw new IllegalArgumentException("项目不能为空");
        requireUser(userId);
        Map<Long, ProjectPermissionScope> scopeByProjectId = permissionScopes(userId);
        // 项目文档搜索沿用文档模块的权限语义，越权请求在访问索引前直接拒绝。
        if (!scopeByProjectId.containsKey(projectId)) {
            throw new ForbiddenOperationException("你不是该项目成员");
        }
        String normalized = normalizeQuery(query);
        if (normalized.isEmpty()) return List.of();
        SearchScope searchScope = new SearchScope(List.of(projectId), List.of(ProjectContentType.DOCUMENT));
        return search(normalized, scopeByProjectId, searchScope, GLOBAL_CHANNELS);
    }

    private String normalizeQuery(String query) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty()) return normalized;
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("搜索内容不能超过 " + MAX_QUERY_LENGTH + " 个字符");
        }
        return normalized;
    }

    private String normalizeTaskNumber(String taskNumber) {
        String normalized = taskNumber.replaceFirst("^0+(?!$)", "");
        return normalized;
    }

    private List<ProjectContentSearchResponse> toTaskSearchResults(List<ProjectContentSearchResponse> results) {
        return results.stream()
                .map(result -> new ProjectContentSearchResponse(
                        result.contentType(), result.resourceId(), result.projectId(),
                        result.projectIdentifier(), result.projectName(), result.title(),
                        ProjectContentTextExtractor.excerpt(
                                ProjectContentTextExtractor.extract(ProjectContentType.TASK, result.excerpt()))))
                .toList();
    }

    private Map<Long, ProjectPermissionScope> permissionScopes(Long userId) {
        List<ProjectPermissionScope> scopes = projectMemberMapper.selectSearchPermissionScopes(userId);
        Map<Long, ProjectPermissionScope> scopeByProjectId = new LinkedHashMap<>();
        scopes.forEach(scope -> scopeByProjectId.put(scope.projectId(), scope));
        return scopeByProjectId;
    }

    private void requireUser(Long userId) {
        if (userId == null) throw new IllegalArgumentException("当前用户未登录");
    }

    private List<ProjectContentSearchResponse> search(String normalized,
                                                      Map<Long, ProjectPermissionScope> scopeByProjectId,
                                                      SearchScope searchScope,
                                                      List<SearchChannel> channels) {
        // 搜索入口只声明不可变通道集合，候选合并、排序与权限复核始终走同一条路径。
        List<RankedHit> ranked = new ArrayList<>(ProjectContentSearchIndex.CHANNEL_LIMIT * channels.size());
        for (SearchChannel channel : channels) {
            addRanked(ranked, searchChannel(channel, searchScope, normalized), channel);
        }
        ranked.sort(RANKING);

        Map<ContentIdentity, RankedHit> unique = new LinkedHashMap<>();
        for (RankedHit hit : ranked) {
            if (scopeByProjectId.containsKey(hit.hit().projectId())) {
                unique.putIfAbsent(identity(hit.hit()), hit);
            }
        }
        return unique.values().stream().limit(MAX_RESULTS)
                .map(hit -> toResponse(hit.hit(), scopeByProjectId.get(hit.hit().projectId())))
                .toList();
    }

    private List<ProjectContentSearchIndex.SearchHit> searchChannel(SearchChannel channel,
                                                                     SearchScope scope,
                                                                     String query) {
        return switch (channel) {
            case TITLE -> searchIndex.searchTitle(scope, query);
            case BODY -> searchIndex.searchBody(scope, query);
            case SEMANTIC -> searchIndex.searchSemantic(scope, query);
        };
    }

    private static final Comparator<RankedHit> RANKING = Comparator
            .comparingInt((RankedHit item) -> item.channel().priority)
            .thenComparing((left, right) -> left.channel() == SearchChannel.SEMANTIC
                    ? Double.compare(right.hit().score(), left.hit().score()) : 0)
            .thenComparing((RankedHit item) -> item.hit().sourceUpdatedAtEpoch(), Comparator.reverseOrder())
            .thenComparing(item -> item.hit().numericId());

    private static void addRanked(List<RankedHit> target, List<ProjectContentSearchIndex.SearchHit> hits,
                                  SearchChannel channel) {
        hits.stream().limit(ProjectContentSearchIndex.CHANNEL_LIMIT)
                .map(hit -> new RankedHit(channel, hit)).forEach(target::add);
    }

    private static ProjectContentSearchResponse toResponse(ProjectContentSearchIndex.SearchHit hit,
                                                            ProjectPermissionScope scope) {
        return new ProjectContentSearchResponse(hit.contentType().toLowerCase(Locale.ROOT), hit.resourceId(),
                hit.projectId(), scope.projectIdentifier(), scope.projectName(), hit.title(), hit.excerpt());
    }

    private static ContentIdentity identity(ProjectContentSearchIndex.SearchHit hit) {
        return new ContentIdentity(hit.contentType(), hit.numericId());
    }

    private enum SearchChannel {
        TITLE(0), BODY(1), SEMANTIC(2);
        private final int priority;
        SearchChannel(int priority) { this.priority = priority; }
    }

    private record RankedHit(SearchChannel channel, ProjectContentSearchIndex.SearchHit hit) {
    }

    private record ContentIdentity(String contentType, Long numericId) {
    }
}
