package com.linearlite.server.service;

import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentFavoriteMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectDocumentQueryServiceTest {
    @Mock private ProjectDocumentMapper documentMapper;
    @Mock private ProjectDocumentFavoriteMapper favoriteMapper;
    @Mock private ProjectDocumentRevisionMapper revisionMapper;
    @Mock private ProjectAccessGuard accessGuard;

    private ProjectDocumentQueryService service;

    @BeforeEach
    void setUp() {
        service = new ProjectDocumentQueryService(documentMapper, favoriteMapper, revisionMapper, accessGuard);
    }

    @Test
    void listTreeUsesTreeProjectionInsteadOfLoadingDocumentContent() {
        List<ProjectDocumentTreeNode> nodes = List.of(new ProjectDocumentTreeNode(
                68L, 7L, null, "安全扫描", 0, 3L, true, LocalDateTime.of(2026, 7, 30, 14, 0)));
        when(documentMapper.selectTreeNodes(7L, 9L, false)).thenReturn(nodes);

        List<ProjectDocumentTreeNode> result = service.listTree(7L, 9L, false);

        assertEquals(nodes, result);
        verify(accessGuard).requireMember(7L, 9L);
        verify(documentMapper).selectTreeNodes(7L, 9L, false);
        // 通用实体查询会连同 LONGTEXT content_json 一起读取，树加载禁止走该路径。
        verify(documentMapper, never()).selectList(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void treeProjectionNeverSelectsDocumentContent() throws NoSuchMethodException {
        Select select = ProjectDocumentMapper.class
                .getMethod("selectTreeNodes", Long.class, Long.class, boolean.class)
                .getAnnotation(Select.class);
        String sql = String.join(" ", select.value());

        assertFalse(sql.contains("content_json"));
    }
}
