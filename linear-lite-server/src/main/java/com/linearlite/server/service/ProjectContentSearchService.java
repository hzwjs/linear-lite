package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.ProjectContentSearchResponse;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.SearchableProjectContent;
import com.linearlite.server.mapper.ProjectContentSearchMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 在用户有权访问的项目中统一检索任务和项目文档。 */
@Service
public class ProjectContentSearchService {
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int EXCERPT_LENGTH = 180;

    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectContentSearchMapper contentMapper;
    private final ProjectContentSemanticSearchService semanticSearchService;

    public ProjectContentSearchService(ProjectMemberMapper projectMemberMapper,
                                       ProjectContentSearchMapper contentMapper,
                                       ProjectContentSemanticSearchService semanticSearchService) {
        this.projectMemberMapper = projectMemberMapper;
        this.contentMapper = contentMapper;
        this.semanticSearchService = semanticSearchService;
    }

    public List<ProjectContentSearchResponse> search(String query, Long userId) {
        if (userId == null) throw new IllegalArgumentException("当前用户未登录");
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty()) return List.of();
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("搜索内容不能超过 " + MAX_QUERY_LENGTH + " 个字符");
        }

        List<Long> projectIds = projectMemberMapper.selectList(
                        new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getUserId, userId))
                .stream().map(ProjectMember::getProjectId).distinct().toList();
        if (projectIds.isEmpty()) return List.of();

        List<SearchableProjectContent> accessible = contentMapper.selectAccessibleContents(projectIds);
        Map<ContentIdentity, SearchableProjectContent> accessibleByIdentity = new LinkedHashMap<>();
        accessible.forEach(content -> accessibleByIdentity.put(identity(content), content));

        String literalQuery = normalized.toLowerCase(Locale.ROOT);
        Map<ContentIdentity, SearchableProjectContent> ordered = new LinkedHashMap<>();
        // 标题、正文和向量命中采用固定排序，避免不同资源类型形成两套结果路径。
        accessible.stream().filter(content -> contains(content.getTitle(), literalQuery))
                .forEach(content -> ordered.put(identity(content), content));
        accessible.stream().filter(content -> contains(semanticSearchService.visibleText(content), literalQuery))
                .forEach(content -> ordered.putIfAbsent(identity(content), content));
        semanticSearchService.search(projectIds, normalized).stream()
                .map(hit -> accessibleByIdentity.get(new ContentIdentity(hit.contentType(), hit.resourceId())))
                .filter(content -> content != null)
                .forEach(content -> ordered.putIfAbsent(identity(content), content));

        return ordered.values().stream().map(this::toResponse).toList();
    }

    private ProjectContentSearchResponse toResponse(SearchableProjectContent content) {
        String visible = semanticSearchService.visibleText(content);
        String excerpt = visible.length() <= EXCERPT_LENGTH ? visible : visible.substring(0, EXCERPT_LENGTH);
        return new ProjectContentSearchResponse(
                content.getContentType().toLowerCase(Locale.ROOT), content.getResourceId(), content.getProjectId(),
                content.getProjectIdentifier(), content.getProjectName(),
                content.getTitle(), excerpt);
    }

    private static ContentIdentity identity(SearchableProjectContent content) {
        return new ContentIdentity(content.getContentType(), content.getResourceId());
    }

    private static boolean contains(String text, String lowercaseQuery) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowercaseQuery);
    }

    private record ContentIdentity(String contentType, String resourceId) {
    }
}
