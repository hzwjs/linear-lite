package com.linearlite.server.dto;

/** GitLab 项目 Web URL，例如 https://gitlab.example.com/group/repository。 */
public class CreateGitLabRepositoryRequest {
    private String repositoryUrl;

    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String repositoryUrl) { this.repositoryUrl = repositoryUrl; }
}
