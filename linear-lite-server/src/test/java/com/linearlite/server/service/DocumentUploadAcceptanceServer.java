package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.linearlite.server.config.*;
import com.linearlite.server.controller.ProjectDocumentAttachmentController;
import com.linearlite.server.controller.ProjectDocumentMultipartUploadController;
import com.linearlite.server.controller.DocumentAssetController;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.exception.GlobalExceptionHandler;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.util.JwtUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验收入口使用真实 HTTP、JWT、上传服务和 R2；MyBatis Mapper 由内存替身隔离。不会连接数据库。 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
@EnableConfigurationProperties({JwtProperties.class, R2StorageProperties.class})
@EnableTransactionManagement
@Import({ProjectDocumentMultipartUploadService.class, ProjectDocumentMultipartUploadController.class, DocumentUploadPartCache.class,
        ProjectDocumentAttachmentService.class, ProjectDocumentAttachmentController.class, DocumentAssetController.class, DocumentImageProcessor.class,
        R2ObjectStorageService.class, S3R2StorageClient.class, JwtUtil.class,
        JwtAuthFilter.class, GlobalExceptionHandler.class, DocumentUploadAcceptanceServer.Controls.class})
public class DocumentUploadAcceptanceServer {
    private final Map<Long, ProjectDocumentAttachment> rows = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DocumentUploadAcceptanceServer.class);
        application.setDefaultProperties(Map.of("server.port", "19080", "spring.main.banner-mode", "off"));
        application.run(args);
    }

    @Bean
    ProjectDocumentMapper documents() {
        var mapper = mock(ProjectDocumentMapper.class);
        var document = new ProjectDocument();
        document.setId(900001L);
        document.setProjectId(6L);
        when(mapper.selectById(900001L)).thenReturn(document);
        return mapper;
    }

    @Bean
    ProjectAccessGuard access() { return mock(ProjectAccessGuard.class); }

    @Bean
    software.amazon.awssdk.services.s3.S3Client r2S3Client(R2StorageProperties properties) {
        return new R2StorageConfig().r2S3Client(properties);
    }

    @Bean
    ProjectDocumentAttachmentMapper attachments() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "acceptance"), ProjectDocumentAttachment.class);
        var mapper = mock(ProjectDocumentAttachmentMapper.class);
        when(mapper.insert(any(ProjectDocumentAttachment.class))).thenAnswer(invocation -> {
            ProjectDocumentAttachment row = invocation.getArgument(0);
            row.setId(sequence.incrementAndGet());
            rows.put(row.getId(), row);
            return 1;
        });
        when(mapper.selectOne(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<ProjectDocumentAttachment> query = invocation.getArgument(0);
            query.getSqlSegment();
            Collection<Object> values = query.getParamNameValuePairs().values();
            return rows.values().stream().filter(row -> values.contains(row.getSourceId()) || values.contains(row.getId())).findFirst().orElse(null);
        });
        when(mapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(rows.values()));
        when(mapper.deleteById(anyLong())).thenAnswer(invocation -> rows.remove(invocation.getArgument(0)) == null ? 0 : 1);
        when(mapper.selectCount(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<ProjectDocumentAttachment> query = invocation.getArgument(0);
            query.getSqlSegment();
            return rows.values().stream().filter(row -> query.getParamNameValuePairs().containsValue(row.getObjectKey())).count();
        });
        return mapper;
    }

    @Bean
    PlatformTransactionManager transactions() {
        return new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        };
    }

    @Bean
    WebMvcConfigurer faultInjection() {
        return new WebMvcConfigurer() {
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response, Object handler) throws Exception {
                        String uri = request.getRequestURI();
                        if (!uri.matches(".*/attachment-uploads/parts/\\d+$")) return true;
                        int part = Integer.parseInt(uri.substring(uri.lastIndexOf('/') + 1));
                        Controls.attempts.add(part);
                        if (part == 2 && Controls.failures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                            response.setStatus(503);
                            response.setCharacterEncoding("UTF-8");
                            response.setContentType("application/json");
                            response.getWriter().write("{\"code\":503,\"message\":\"验收模拟网络中断\",\"data\":null}");
                            return false;
                        }
                        return true;
                    }
                });
            }
        };
    }

    @RestController
    static class Controls {
        private final org.springframework.core.env.Environment environment;
        Controls(org.springframework.core.env.Environment environment) { this.environment = environment; }
        static final AtomicInteger failures = new AtomicInteger();
        static final List<Integer> attempts = new CopyOnWriteArrayList<>();
        // 故障注入入口限定在验收启动类中，产品应用不会加载该控制器。
        @PostMapping("/__acceptance/health")
        Map<String, String> health() { return Map.of("metadata", "in-memory", "storage", "real-r2",
                "r2Enabled", environment.getProperty("app.storage.r2.enabled", "missing")); }
        @PostMapping("/api/__acceptance/session")
        void session(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
            String token = request.getHeader("Authorization").substring("Bearer ".length());
            com.linearlite.server.util.DocumentAssetCookie.write(response, token, 300, false);
        }
        @PostMapping("/__acceptance/fault")
        void fault(@RequestParam int count) { failures.set(count); attempts.clear(); }
        @GetMapping("/__acceptance/state")
        Map<String, Object> state() { return Map.of("attemptedParts", attempts, "remainingFailures", failures.get()); }
    }
}
