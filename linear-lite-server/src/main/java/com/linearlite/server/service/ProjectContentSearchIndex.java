package com.linearlite.server.service;

import com.linearlite.server.entity.SearchableProjectContent;

import java.util.List;

/** 项目内容精准与语义搜索的数据库无关 Interface。 */
public interface ProjectContentSearchIndex {
    int CHANNEL_LIMIT = 50;

    List<SearchHit> searchTitle(SearchScope scope, String query);

    List<SearchHit> searchBody(SearchScope scope, String query);

    List<SearchHit> searchSemantic(SearchScope scope, String query);

    void ensureCollection();

    void upsert(SearchableProjectContent content);

    void delete(String contentType, Long numericId);

    String contentHash(SearchableProjectContent content);

    record SearchHit(String contentType, Long numericId, String resourceId, Long projectId,
                     String title, String excerpt, long sourceUpdatedAtEpoch, double score) {
    }

    /** 搜索范围必须同时声明项目权限快照和内容类型，确保约束下推到每个索引通道。 */
    record SearchScope(List<Long> projectIds, List<ProjectContentType> contentTypes) {
        public SearchScope {
            projectIds = List.copyOf(projectIds);
            contentTypes = List.copyOf(contentTypes);
            if (projectIds.isEmpty()) throw new IllegalArgumentException("项目权限不能为空");
            if (contentTypes.isEmpty()) throw new IllegalArgumentException("搜索内容类型不能为空");
        }
    }
}
