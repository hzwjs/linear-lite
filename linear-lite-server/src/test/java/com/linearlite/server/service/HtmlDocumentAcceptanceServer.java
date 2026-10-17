package com.linearlite.server.service;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.controller.ProjectDocumentController;
import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.exception.GlobalExceptionHandler;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.mapper.*;
import com.linearlite.server.util.JwtUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 HTTP/JWT/文档服务/HTML 解析器验收。Mapper 为内存替身，不连接数据库。 */
@Configuration
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
@EnableConfigurationProperties(JwtProperties.class)
@EnableTransactionManagement
@Import({ProjectDocumentCommandService.class, ProjectDocumentQueryService.class,
        DocumentRevisionSnapshotService.class, ProjectDocumentController.class,
        JwtUtil.class, JwtAuthFilter.class, GlobalExceptionHandler.class, HtmlDocumentAcceptanceServer.Fixtures.class})
public class HtmlDocumentAcceptanceServer {
    private final Map<Long, ProjectDocument> rows = new ConcurrentHashMap<>();
    private final Map<Long, ProjectDocumentRevision> versions = new ConcurrentHashMap<>();
    private final AtomicLong documentIds = new AtomicLong(8000);
    private final AtomicLong revisionIds = new AtomicLong(9000);
    private final Map<Long, String> collaborationContents = new ConcurrentHashMap<>();
    private final Map<Long, String> collaborationEpochs = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HtmlDocumentAcceptanceServer.class);
        app.setDefaultProperties(Map.of("server.port", "19081", "server.address", "127.0.0.1",
                "spring.main.banner-mode", "off", "jwt.secret", "html-acceptance-fixture-key-not-a-production-secret-2026"));
        app.run(args);
    }

    private ProjectDocument copy(ProjectDocument row) {
        if (row == null) return null;
        ProjectDocument result = new ProjectDocument();
        BeanUtils.copyProperties(row, result);
        return result;
    }
    private List<ProjectDocumentTreeNode> tree() {
        return rows.values().stream().filter(row -> row.getArchivedAt() == null)
                .map(row -> new ProjectDocumentTreeNode(row.getId(), row.getProjectId(), row.getParentDocumentId(),
                        row.getTitle(), row.getSortOrder(), row.getVersion(), false, row.getUpdatedAt(), row.getContentFormat()))
                .toList();
    }
    private static Object queryValue(LambdaQueryWrapper<?> query, int index) {
        query.getSqlSegment();
        return query.getParamNameValuePairs().get("MPGENVAL" + index);
    }

    @Bean ProjectDocumentMapper documents() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "html-test"), ProjectDocument.class);
        var mapper = mock(ProjectDocumentMapper.class);
        when(mapper.insert(any(ProjectDocument.class))).thenAnswer(call -> {
            ProjectDocument row = call.getArgument(0);
            row.setId(documentIds.incrementAndGet());
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            rows.put(row.getId(), copy(row));
            return 1;
        });
        when(mapper.selectById(anyLong())).thenAnswer(call -> copy(rows.get(call.getArgument(0))));
        when(mapper.selectByIdForUpdate(anyLong())).thenAnswer(call -> copy(rows.get(call.getArgument(0))));
        when(mapper.selectList(any())).thenAnswer(call -> rows.values().stream().map(this::copy).toList());
        when(mapper.selectTreeNodes(anyLong(), anyLong(), eq(false))).thenAnswer(call -> tree());
        when(mapper.selectFavoriteTreeNodes(anyLong())).thenReturn(List.of());
        when(mapper.selectTreeNodes(anyLong(), anyLong(), eq(true))).thenReturn(List.of());
        when(mapper.updatePosition(anyLong(), nullable(Long.class), anyInt())).thenAnswer(call -> {
            ProjectDocument row = rows.get(call.getArgument(0));
            row.setParentDocumentId(call.getArgument(1)); row.setSortOrder(call.getArgument(2)); return 1;
        });
        return mapper;
    }

    @Bean ProjectDocumentRevisionMapper revisions() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "html-revision-test"), ProjectDocumentRevision.class);
        var mapper = mock(ProjectDocumentRevisionMapper.class);
        when(mapper.insert(any(ProjectDocumentRevision.class))).thenAnswer(call -> {
            ProjectDocumentRevision row = call.getArgument(0);
            row.setId(revisionIds.incrementAndGet()); row.setCreatedAt(LocalDateTime.now());
            versions.put(row.getId(), row); return 1;
        });
        when(mapper.selectOne(any())).thenAnswer(call -> {
            LambdaQueryWrapper<ProjectDocumentRevision> query = call.getArgument(0);
            Object documentId = queryValue(query, 1);
            Object second = queryValue(query, 2);
            boolean byVersion = query.getSqlSegment().contains("version =");
            return versions.values().stream().filter(row -> row.getDocumentId().equals(documentId))
                    .filter(row -> second == null || (byVersion ? row.getVersion().equals(second) : row.getId().equals(second)))
                    .max(Comparator.comparing(ProjectDocumentRevision::getId)).orElse(null);
        });
        when(mapper.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<ProjectDocumentRevision> query = call.getArgument(0);
            Object id = queryValue(query, 1);
            return versions.values().stream().filter(row -> row.getDocumentId().equals(id))
                    .sorted(Comparator.comparing(ProjectDocumentRevision::getId).reversed()).toList();
        });
        return mapper;
    }
    @Bean ProjectDocumentFavoriteMapper favorites() {
        var mapper = mock(ProjectDocumentFavoriteMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        return mapper;
    }
    @Bean ProjectAccessGuard access() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "html-member-test"),
                com.linearlite.server.entity.ProjectMember.class);
        var memberMapper = mock(ProjectMemberMapper.class);
        when(memberMapper.selectCount(any())).thenAnswer(call -> {
            LambdaQueryWrapper<?> query = call.getArgument(0);
            return Long.valueOf(3).equals(queryValue(query, 1)) && Long.valueOf(7).equals(queryValue(query, 2)) ? 1L : 0L;
        });
        return new ProjectAccessGuard(mock(ProjectMapper.class), memberMapper);
    }
    @Bean UserMapper users() {
        var mapper = mock(UserMapper.class);
        when(mapper.selectBatchIds(any())).thenReturn(List.of());
        return mapper;
    }
    @Bean ProjectDocumentAttachmentService attachments() { return mock(ProjectDocumentAttachmentService.class); }
    @Bean ProjectContentSearchService search() { return mock(ProjectContentSearchService.class); }
    @Bean DocumentCollaborationService collaboration() {
        DocumentCollaborationService service = mock(DocumentCollaborationService.class);
        doAnswer(call -> {
            ProjectDocument row = call.getArgument(0);
            collaborationContents.put(row.getId(), call.getArgument(1));
            collaborationEpochs.put(row.getId(), UUID.randomUUID().toString());
            return null;
        }).when(service).createInitialState(any(ProjectDocument.class), anyString());
        when(service.getSnapshot(anyLong(), anyBoolean())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            ProjectDocument row = rows.get(id);
            return new CollaborationNodeClient.SnapshotResponse(collaborationEpochs.get(id), row.getVersion(),
                    row.getContentFormat(), collaborationContents.get(id));
        });
        return service;
    }
    @Bean CollaborationNodeClient nodeClient() {
        CollaborationNodeClient client = mock(CollaborationNodeClient.class);
        when(client.command(anyLong(), any(CollaborationNodeClient.CommandRequest.class))).thenAnswer(call -> {
            Long id = call.getArgument(0);
            CollaborationNodeClient.CommandRequest request = call.getArgument(1);
            ProjectDocument row = rows.get(id);
            long nextVersion = row.getVersion() + 1;
            row.setVersion(nextVersion);
            row.setTitle(request.title());
            row.setLastEditorId(request.actorId());
            row.setUpdatedAt(LocalDateTime.now());
            rows.put(id, copy(row));
            collaborationContents.put(id, request.content());
            return new CollaborationNodeClient.CommandResponse(request.epoch(), 2L, nextVersion,
                    request.contentFormat(), request.content(), request.title());
        });
        return client;
    }
    @Bean PlatformTransactionManager transactions() {
        return new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        };
    }

    @RestController
    static class Fixtures {
        private final JwtUtil jwt;
        Fixtures(JwtUtil jwt) { this.jwt = jwt; }
        @PostMapping("/api/auth/login") ApiResponse<Map<String, Object>> login() {
            return ApiResponse.success(Map.of("token", jwt.generateToken(7L, "html-test"), "userId", 7L, "username", "html-test"));
        }
        @PostMapping({"/api/auth/document-asset-session", "/api/auth/logout"}) ApiResponse<Void> session() { return ApiResponse.success(); }
        @GetMapping("/api/projects") ApiResponse<?> projects() {
            return ApiResponse.success(List.of(Map.of("id", 3, "name", "HTML 验收项目", "identifier", "HTML", "sortOrder", 0)));
        }
        @GetMapping({"/api/projects/3/members", "/api/favorites", "/api/notifications"}) ApiResponse<?> empty() { return ApiResponse.success(List.of()); }
        @GetMapping("/api/notifications/unread-count") ApiResponse<?> unread() { return ApiResponse.success(Map.of("count", 0)); }
    }
}
