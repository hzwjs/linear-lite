package com.linearlite.server.dto;

/** GitHub 仓库 Web URL，例如 https://github.com/org/repository。 */
public class CreateGitHubRepositoryRequest {
    private String repositoryUrl;
    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String repositoryUrl) { this.repositoryUrl = repositoryUrl; }
}
