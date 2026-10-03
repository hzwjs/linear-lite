package com.linearlite.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentUploadPartCacheTest {
    @TempDir Path root;

    @Test
    void confirmedChunksProduceWholeFileHashWithoutReadingStorage() throws Exception {
        var cache = new DocumentUploadPartCache(root);
        byte[] first = new byte[(int) ProjectDocumentMultipartUploadService.PART_SIZE];
        byte[] last = {1, 2, 3};
        cache.put("session", 1, first);
        cache.put("session", 2, last);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        MessageDigest composite = MessageDigest.getInstance("MD5");
        composite.update(md5.digest(first)); composite.update(md5.digest(last));
        String etag = HexFormat.of().formatHex(composite.digest()) + "-2";
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(first); sha.update(last);
        var result = cache.verify(session(first.length + last.length), '"' + etag + '"',
                () -> { throw new AssertionError("must not read R2 when confirmed chunks are cached"); }, mock(DocumentImageProcessor.class));
        assertEquals(HexFormat.of().formatHex(sha.digest()), result.sha256());
    }

    @Test
    void corruptedCacheCannotCreateMetadataAndIsEvicted() throws Exception {
        var cache = new DocumentUploadPartCache(root);
        cache.put("session", 1, new byte[] {1, 2, 4});
        assertThrows(IllegalArgumentException.class, () -> cache.verify(session(3), etag(new byte[] {1, 2, 3}),
                () -> { throw new AssertionError(); }, mock(DocumentImageProcessor.class)));
        assertFalse(Files.exists(root.resolve("session")));
    }

    @Test
    void missingCacheReconstructsFromObjectAndValidatesTheSameChecksum() throws Exception {
        byte[] bytes = {1, 2, 3};
        var result = new DocumentUploadPartCache(root).verify(session(3), etag(bytes),
                () -> new ByteArrayInputStream(bytes), mock(DocumentImageProcessor.class));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), result.sha256());
    }

    private ProjectDocumentMultipartUploadService.Session session(long size) {
        return new ProjectDocumentMultipartUploadService.Session("session", 11L, 7L, 5L, "key", "upload", "file.pdf", size, "application/pdf");
    }
    private String etag(byte[] bytes) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(md5.digest(md5.digest(bytes))) + "-1";
    }
}
