package com.linearlite.server.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CollaborationPathScopeServiceTest {
    @Test
    void canonicalPathsAndDirectoryContainment() {
        assertTrue(CollaborationPathScopeService.overlaps("src/", "src/main/App.java"));
        assertTrue(CollaborationPathScopeService.overlaps("src", "src/main/App.java"));
        assertTrue(CollaborationPathScopeService.overlaps("src/main/App.java", "src"));
        assertTrue(CollaborationPathScopeService.overlaps("src/", "src"));
        assertTrue(CollaborationPathScopeService.overlaps("src/a", "src/a"));
        assertFalse(CollaborationPathScopeService.overlaps("src/a", "src/ab"));
        assertFalse(CollaborationPathScopeService.overlaps("src/", "src2/a"));
        assertFalse(CollaborationPathScopeService.overlaps("src", "src2/a"));
        assertFalse(CollaborationPathScopeService.overlaps("src/a", "src/b"));
        for (String invalid : List.of("../x", "/src", "src//x", "src/./x", "src\\x"))
            assertThrows(IllegalArgumentException.class, () -> CollaborationPathScopeService.validate(List.of(invalid)));
        assertThrows(IllegalArgumentException.class, () -> CollaborationPathScopeService.validate(List.of("src", "src/")));
    }
}
