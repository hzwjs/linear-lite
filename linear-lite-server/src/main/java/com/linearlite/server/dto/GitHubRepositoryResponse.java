package com.linearlite.server.dto;

import java.time.LocalDateTime;

/** webhookSecret 仅在创建或重置时返回，普通列表永不回传。 */
public record GitHubRepositoryResponse(Long id, String repositoryUrl, String repositoryPath,
                                       String webhookSecret, LocalDateTime createdAt) {
}
