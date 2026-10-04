package com.linearlite.server.service;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linearlite.server.config.*;
import com.linearlite.server.controller.*;
import com.linearlite.server.dto.ImageUploadResponse;
import com.linearlite.server.entity.*;
import com.linearlite.server.exception.GlobalExceptionHandler;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.mapper.*;
import com.linearlite.server.util.DocumentAssetCookie;
import com.linearlite.server.util.JwtUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 HTTP/JWT/导入/分片上传/资源读取；数据库和对象存储使用内存替身。 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
@EnableConfigurationProperties({JwtProperties.class, R2StorageProperties.class})
@EnableTransactionManagement
@Import({DocumentRemoteImageService.class, DocumentImageImportController.class, ProjectDocumentAttachmentService.class,
        ProjectDocumentAttachmentController.class, DocumentAssetController.class, DocumentImageProcessor.class,
        ProjectDocumentMultipartUploadService.class, ProjectDocumentMultipartUploadController.class, DocumentUploadPartCache.class,
        JwtUtil.class, JwtAuthFilter.class, GlobalExceptionHandler.class, DocumentImagePasteAcceptanceServer.Login.class})
public class DocumentImagePasteAcceptanceServer {
    private final Map<Long, ProjectDocumentAttachment> rows = new ConcurrentHashMap<>();
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, String> etags = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, byte[]>> parts = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> metadata = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    public static void main(String[] args) {
        var app = new SpringApplication(DocumentImagePasteAcceptanceServer.class);
        app.setDefaultProperties(Map.of("server.port", "19081", "spring.main.banner-mode", "off"));
        app.run(args);
    }

    @Bean ProjectDocumentMapper documents() {
        var mapper = mock(ProjectDocumentMapper.class);
        var document = new ProjectDocument(); document.setId(900001L); document.setProjectId(6L);
        when(mapper.selectById(900001L)).thenReturn(document);
        return mapper;
    }
    @Bean ProjectAccessGuard access() {
        var guard = mock(ProjectAccessGuard.class);
        doAnswer(call -> {
            if (!Long.valueOf(5).equals(call.getArgument(1))) throw new com.linearlite.server.exception.ForbiddenOperationException("无文档权限");
            return null;
        }).when(guard).requireMember(anyLong(), anyLong());
        return guard;
    }
    @Bean ProjectDocumentAttachmentMapper attachments() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "image-paste"), ProjectDocumentAttachment.class);
        var mapper = mock(ProjectDocumentAttachmentMapper.class);
        when(mapper.insert(any(ProjectDocumentAttachment.class))).thenAnswer(call -> {
            ProjectDocumentAttachment row = call.getArgument(0); row.setId(sequence.incrementAndGet()); rows.put(row.getId(), row); return 1;
        });
        when(mapper.selectOne(any())).thenAnswer(call -> {
            LambdaQueryWrapper<ProjectDocumentAttachment> query = call.getArgument(0); query.getSqlSegment();
            var values = query.getParamNameValuePairs().values();
            return rows.values().stream().filter(row -> values.contains(row.getId())
                    || row.getSourceId() != null && values.contains(row.getSourceId())).findFirst().orElse(null);
        });
        when(mapper.selectList(any())).thenAnswer(call -> new ArrayList<>(rows.values()));
        when(mapper.deleteById(anyLong())).thenAnswer(call -> rows.remove(call.getArgument(0)) == null ? 0 : 1);
        return mapper;
    }
    @Bean ObjectStorageService objects() {
        var service = mock(ObjectStorageService.class);
        when(service.uploadProjectDocumentAttachment(any(InputStream.class), anyLong(), any(), any(), anyLong(), anyLong(), anyLong())).thenAnswer(call -> {
            String key = "images/" + UUID.randomUUID(); objects.put(key, ((InputStream) call.getArgument(0)).readAllBytes());
            return new ImageUploadResponse("/unused", key);
        });
        when(service.uploadProjectDocumentThumbnail(any(), any(), anyLong(), anyLong())).thenAnswer(call -> {
            String key = "thumbnails/" + UUID.randomUUID(); objects.put(key, call.getArgument(0)); return key;
        });
        when(service.openObjectStreamByKey(any())).thenAnswer(call -> new ByteArrayInputStream(objects.get(call.getArgument(0))));
        when(service.copyProjectDocumentAttachmentObject(any(), any(), anyLong(), anyLong())).thenAnswer(call -> {
            String key = "clones/" + UUID.randomUUID(); objects.put(key, objects.get(call.getArgument(0))); return key;
        });
        doAnswer(call -> { objects.remove(call.getArgument(0)); return null; }).when(service).deleteObjectByKey(any());
        return service;
    }
    @Bean S3Client s3() {
        var s3 = mock(S3Client.class);
        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class))).thenAnswer(call -> {
            CreateMultipartUploadRequest request = call.getArgument(0); String id = UUID.randomUUID().toString();
            parts.put(id, new ConcurrentHashMap<>()); metadata.put(request.key(), request.metadata());
            return CreateMultipartUploadResponse.builder().uploadId(id).build();
        });
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            UploadPartRequest request = call.getArgument(0); RequestBody body = call.getArgument(1);
            try (var stream = body.contentStreamProvider().newStream()) { parts.get(request.uploadId()).put(request.partNumber(), stream.readAllBytes()); }
            return UploadPartResponse.builder().eTag("part-" + request.partNumber()).build();
        });
        when(s3.listParts(any(ListPartsRequest.class))).thenAnswer(call -> {
            ListPartsRequest request = call.getArgument(0);
            return ListPartsResponse.builder().parts(parts.get(request.uploadId()).entrySet().stream().map(entry ->
                    Part.builder().partNumber(entry.getKey()).size((long) entry.getValue().length).eTag("part-" + entry.getKey()).build()).toList()).build();
        });
        when(s3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).thenAnswer(call -> {
            CompleteMultipartUploadRequest request = call.getArgument(0); var output = new ByteArrayOutputStream();
            var md5 = java.security.MessageDigest.getInstance("MD5");
            var ordered = new TreeMap<>(parts.get(request.uploadId()));
            for (var entry : ordered.values()) {
                output.write(entry); md5.update(java.security.MessageDigest.getInstance("MD5").digest(entry));
            }
            String etag = HexFormat.of().formatHex(md5.digest()) + "-" + ordered.size();
            etags.put(request.key(), etag);
            objects.put(request.key(), output.toByteArray()); return CompleteMultipartUploadResponse.builder().eTag(etag).build();
        });
        when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(call -> {
            HeadObjectRequest request = call.getArgument(0);
            if (!objects.containsKey(request.key())) throw S3Exception.builder().statusCode(404).message("missing").build();
            return HeadObjectResponse.builder().contentLength((long) objects.get(request.key()).length).metadata(metadata.get(request.key())).eTag(etags.get(request.key())).build();
        });
        return s3;
    }
    @Bean PlatformTransactionManager transactions() {
        return new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object value, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        };
    }
    @RestController static class Login {
        private final JwtUtil jwt;
        Login(JwtUtil jwt) { this.jwt = jwt; }
        @PostMapping("/api/auth/login") Map<String, String> login(jakarta.servlet.http.HttpServletResponse response) {
            String token = jwt.generateToken(5L, "图片粘贴验收");
            DocumentAssetCookie.write(response, token, 600, false);
            return Map.of("token", token);
        }
    }
}
