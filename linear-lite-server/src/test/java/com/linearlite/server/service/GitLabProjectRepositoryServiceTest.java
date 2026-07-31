package com.linearlite.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitLabProjectRepositoryServiceTest {

    @Test
    void normalizesConfiguredGitLabWebUrlIntoFixedRepositoryIdentity() {
        GitLabProjectRepositoryService.RepositoryIdentity identity =
                GitLabProjectRepositoryService.normalizeRepositoryUrl("HTTPS://GitLab.Example.com/group/platform/");

        assertEquals("https://gitlab.example.com/group/platform", identity.url());
        assertEquals("group/platform", identity.path());
    }

    @Test
    void rejectsCloneUrlInsteadOfGuessingRepositoryIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> GitLabProjectRepositoryService.normalizeRepositoryUrl("https://gitlab.example.com/group/platform.git"));
    }
}
