package com.linearlite.server.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectArtifactPathServiceTest {
    @Test void validatesOnlyActualProjectRelativePaths() {
        assertEquals(List.of("src/main/App.java", "docs/说明.md"),
                ProjectArtifactPathService.validate(List.of("src/main/App.java", "docs/说明.md")));
        for (String invalid : List.of("../outside", "/absolute", "src/./a", "src//a", "C:/outside"))
            assertThrows(IllegalArgumentException.class, () -> ProjectArtifactPathService.validate(List.of(invalid)));
        assertThrows(IllegalArgumentException.class, () -> ProjectArtifactPathService.validate(List.of("src", "src/")));
    }
}
