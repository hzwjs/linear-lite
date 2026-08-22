package com.linearlite.server.service;

import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.dto.ProjectDocumentRevisionSummary;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentFavoriteMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import com.linearlite.server.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    @Mock private UserMapper userMapper;
    @Mock private ProjectAccessGuard accessGuard;

    private ProjectDocumentQueryService service;

    @BeforeEach
    void setUp() {
        service = new ProjectDocumentQueryService(documentMapper, favoriteMapper, revisionMapper, userMapper, accessGuard);
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
    void listRevisionsKeepsRevisionWithMissingEditorAndNullName() {
        ProjectDocumentRevision revision = new ProjectDocumentRevision();
        revision.setId(1L);
        revision.setDocumentId(11L);
        revision.setVersion(2L);
        revision.setTitle("快照");
        revision.setContentJson("[]");
        revision.setEditorId(999L);
        revision.setCreatedAt(LocalDateTime.of(2026, 7, 30, 14, 0));

        ProjectDocument document = new ProjectDocument();
        document.setId(11L);
        document.setProjectId(7L);
        document.setTitle("文档");
        document.setSortOrder(0);
        document.setVersion(3L);
        document.setCreatedAt(LocalDateTime.of(2026, 7, 30, 14, 0));

        when(documentMapper.selectById(11L)).thenReturn(document);
        when(revisionMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(revision));
        // 编辑人 999 已被删除，userMapper 不返回该用户，editorName 为 null
        when(userMapper.selectBatchIds(List.of(999L))).thenReturn(List.of());

        List<ProjectDocumentRevisionSummary> result = service.listRevisions(11L, 7L);

        assertEquals(1, result.size());
        // editorId 仍保留，editorName 按接口约定为 null，不抛异常、不丢弃该行
        assertEquals(999L, result.get(0).editorId());
        assertEquals(null, result.get(0).editorName());
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
