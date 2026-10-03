package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.config.R2StorageProperties;
import com.linearlite.server.dto.CreateDocumentAttachmentUploadRequest;
import com.linearlite.server.dto.DocumentAttachmentUploadResponse;
import com.linearlite.server.dto.ProjectDocumentAttachmentResponse;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectDocumentMultipartUploadServiceTest {
    private final S3Client s3 = mock(S3Client.class);
    private final ProjectDocumentMapper documents = mock(ProjectDocumentMapper.class);
    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final ProjectDocumentAttachmentService attachments = mock(ProjectDocumentAttachmentService.class);
    private final ObjectStorageService objects = mock(ObjectStorageService.class);
    private final DocumentImageProcessor images = mock(DocumentImageProcessor.class);
    private final JwtProperties jwt = new JwtProperties();
    private ProjectDocumentMultipartUploadService service;
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path cacheRoot;

    @BeforeEach
    void setUp() {
        R2StorageProperties storage = new R2StorageProperties();
        storage.setBucket("test-bucket");
        jwt.setSecret("test-upload-secret-32-bytes-minimum-key");
        service = new ProjectDocumentMultipartUploadService(s3, storage, jwt, new ObjectMapper(),
                documents, mock(ProjectDocumentAttachmentMapper.class), access, attachments, objects,
                images, new DocumentUploadPartCache(cacheRoot), 2L * 1024 * 1024 * 1024);
        ProjectDocument document = new ProjectDocument();
        document.setId(11L);
        document.setProjectId(7L);
        when(documents.selectById(11L)).thenReturn(document);
    }

    @AfterEach
    void tearDown() { service.close(); }

    @Test
    void sessionIsBoundToMemberAndCannotBeUsedAsLoginToken() {
        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-id").build());
        var response = service.create(11L, 5L, new CreateDocumentAttachmentUploadRequest("report.pdf", 80L * 1024 * 1024, "application/pdf"));
        var session = service.requireSession(11L, 5L, response.sessionToken());
        assertEquals(80L * 1024 * 1024, session.fileSize());
        assertEquals(8L * 1024 * 1024, response.partSize());
        assertThrows(ForbiddenOperationException.class, () -> service.requireSession(12L, 5L, response.sessionToken()));
        assertThrows(ForbiddenOperationException.class, () -> service.requireSession(11L, 6L, response.sessionToken()));
        var jwtUtil = new JwtUtil(jwt);
        assertThrows(IllegalArgumentException.class, () -> jwtUtil.getUserIdFromClaims(jwtUtil.parseToken(response.sessionToken())));
        assertThrows(IllegalArgumentException.class, () -> service.requireSession(11L, 5L, response.sessionToken() + "invalid"));
        verify(access, atLeastOnce()).requireMember(7L, 5L);
    }

    @Test
    void rejectsOversizedFileBeforeCreatingStorageUpload() {
        assertThrows(IllegalArgumentException.class, () -> service.create(11L, 5L,
                new CreateDocumentAttachmentUploadRequest("large.bin", 2147483649L, "application/octet-stream")));
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    @Test
    void missingOrWrongSizedPartsCannotBeConfirmed() {
        var session = session(ProjectDocumentMultipartUploadService.PART_SIZE + 7);
        assertThrows(IllegalArgumentException.class, () -> service.requireAllParts(session, List.of(part(1, ProjectDocumentMultipartUploadService.PART_SIZE))));
        assertThrows(IllegalArgumentException.class, () -> service.requireAllParts(session,
                List.of(part(1, ProjectDocumentMultipartUploadService.PART_SIZE), part(2, 6))));
        assertThrows(IllegalArgumentException.class, () -> service.requireAllParts(session,
                List.of(part(1, ProjectDocumentMultipartUploadService.PART_SIZE), part(3, 7))));
        assertDoesNotThrow(() -> service.requireAllParts(session,
                List.of(part(1, ProjectDocumentMultipartUploadService.PART_SIZE), part(2, 7))));
    }

    @Test
    void partBodyCanBeReplayedAndRejectsUnexpectedLength() throws Exception {
        byte[] bytes = new byte[] {1, 2, 3};
        var session = session(bytes.length);
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            assertEquals(3L, body.contentLength());
            try (var first = body.contentStreamProvider().newStream(); var retry = body.contentStreamProvider().newStream()) {
                assertArrayEquals(bytes, first.readAllBytes());
                assertArrayEquals(bytes, retry.readAllBytes());
            }
            return UploadPartResponse.builder().eTag("etag").build();
        });
        service.uploadPart(session, 1, new MockMultipartFile("file", bytes));
        assertThrows(IllegalArgumentException.class, () -> service.uploadPart(session, 1,
                new MockMultipartFile("file", new byte[4])));
        verify(s3, times(1)).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
    }

    @Test
    void unknownContentTypeIsAnalyzedFromTheSameDownloadedBytes() throws Exception {
        byte[] bytes = "unknown type attachment".getBytes(StandardCharsets.UTF_8);
        var original = session(bytes.length);
        var session = new ProjectDocumentMultipartUploadService.Session(original.id(), original.documentId(), original.projectId(),
                original.userId(), original.objectKey(), original.uploadId(), original.fileName(), original.fileSize(), "application/octet-stream");
        when(s3.listParts(any(ListPartsRequest.class))).thenReturn(ListPartsResponse.builder().parts(part(1, bytes.length)).build());
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) bytes.length).eTag(etag(bytes)).metadata(Map.of("upload-session", session.id())).build());
        when(objects.openObjectStreamByKey(session.objectKey())).thenReturn(new ByteArrayInputStream(bytes));
        when(images.analyzeStream(any())).thenAnswer(invocation -> {
            java.io.InputStream input = invocation.getArgument(0);
            assertArrayEquals(bytes, input.readAllBytes());
            return null;
        });
        var attachment = new ProjectDocumentAttachmentResponse(31L, 7L, 11L, session.sourceId(), "report.pdf", (long) bytes.length,
                "application/octet-stream", "verified", null, null, null, "/download", null);
        when(attachments.registerUploadedAttachment(any(), any(), any(), any(), any(), anyLong(), any(), any(), any(), any())).thenAnswer(ignored -> {
            when(attachments.findUploadedAttachment(11L, session.sourceId(), 5L)).thenReturn(attachment);
            return attachment;
        });
        service.complete(session, "token");
        assertEquals(attachment, awaitCompletion(session).attachment());
        verify(objects, times(1)).openObjectStreamByKey(session.objectKey());
        verify(images).analyzeStream(any());
    }

    @Test
    void resumeUsesStoragePartsAndDoesNotTrustClientEtags() {
        when(s3.listParts(any(ListPartsRequest.class))).thenReturn(ListPartsResponse.builder()
                .parts(part(1, ProjectDocumentMultipartUploadService.PART_SIZE)).build());
        var response = service.status(session(ProjectDocumentMultipartUploadService.PART_SIZE * 2), "token");
        assertEquals("uploading", response.status());
        assertEquals(List.of(new DocumentAttachmentUploadResponse.UploadedPart(1, ProjectDocumentMultipartUploadService.PART_SIZE)), response.parts());
    }

    @Test
    void completionResponseLossResumesFromExistingObjectAndHashesRemoteBytes() throws Exception {
        byte[] bytes = "verified remote file".getBytes(StandardCharsets.UTF_8);
        var session = session(bytes.length);
        when(s3.listParts(any(ListPartsRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) bytes.length).eTag(etag(bytes)).metadata(Map.of("upload-session", session.id())).build());
        when(objects.openObjectStreamByKey(session.objectKey())).thenAnswer(ignored -> new ByteArrayInputStream(bytes));
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var attachment = new ProjectDocumentAttachmentResponse(31L, 7L, 11L, session.sourceId(), "report.pdf", (long) bytes.length,
                "application/pdf", sha256, null, null, null, "/download", null);
        when(attachments.registerUploadedAttachment(eq(11L), eq(5L), eq(session.sourceId()), eq(session.objectKey()), eq("report.pdf"),
                eq((long) bytes.length), eq("application/pdf"), eq(sha256), isNull(), isNull())).thenAnswer(ignored -> {
            when(attachments.findUploadedAttachment(11L, session.sourceId(), 5L)).thenReturn(attachment);
            return attachment;
        });
        assertEquals("uploaded", service.status(session, "token").status());
        service.complete(session, "token");
        var result = awaitCompletion(session);
        assertEquals(attachment, result.attachment());
        assertEquals("completed", result.status());
        verify(s3, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        verify(attachments).registerUploadedAttachment(any(), any(), any(), any(), any(), anyLong(), any(), eq(sha256), any(), any());
    }

    @Test
    void wrongObjectLengthDoesNotCreateAttachment() throws Exception {
        var session = session(4);
        when(s3.listParts(any(ListPartsRequest.class))).thenReturn(ListPartsResponse.builder().parts(part(1, 4)).build());
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(3L).metadata(Map.of("upload-session", session.id())).build());
        try { service.complete(session, "token"); } catch (IllegalStateException expected) { }
        assertThrows(IllegalStateException.class, () -> awaitCompletion(session));
        verify(attachments, never()).registerUploadedAttachment(any(), any(), any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    private DocumentAttachmentUploadResponse awaitCompletion(ProjectDocumentMultipartUploadService.Session session) throws Exception {
        for (int index = 0; index < 100; index++) {
            var status = service.status(session, "token");
            if (!"processing".equals(status.status())) return status;
            Thread.sleep(10);
        }
        throw new AssertionError("Upload confirmation did not complete");
    }

    private ProjectDocumentMultipartUploadService.Session session(long size) {
        return new ProjectDocumentMultipartUploadService.Session("session-id", 11L, 7L, 5L,
                "document-attachment-uploads/7/11/session-id", "upload-id", "report.pdf", size, "application/pdf");
    }
    private Part part(int number, long size) { return Part.builder().partNumber(number).size(size).eTag("etag-" + number).build(); }
    private String etag(byte[] bytes) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(md5.digest(md5.digest(bytes))) + "-1";
    }
}
