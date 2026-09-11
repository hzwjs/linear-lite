package com.linearlite.server.controller;

import com.linearlite.server.config.R2StorageProperties;
import com.linearlite.server.dto.DocumentAssetDescriptor;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectDocumentAttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAssetControllerTest {

    @Mock private ProjectDocumentAttachmentService attachmentService;

    private final R2StorageProperties storageProperties = new R2StorageProperties();
    private DocumentAssetController controller;

    @BeforeEach
    void setUp() {
        storageProperties.setEnabled(true);
        controller = new DocumentAssetController(attachmentService, storageProperties);
    }

    @Test
    void returnsAssetWithBoundedPrivateCacheAndCookieVariance() {
        when(attachmentService.resolveAsset(11L, 31L, "hash", "original", 5L))
                .thenReturn(new DocumentAssetDescriptor("key", "image/png", 42L, "\"sha256:hash\""));

        var response = controller.asset(request(null), 11L, 31L, "hash", "original");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("private, max-age=60, must-revalidate", response.getHeaders().getCacheControl());
        assertEquals("Cookie", response.getHeaders().getFirst(HttpHeaders.VARY));
        assertEquals("\"sha256:hash\"", response.getHeaders().getETag());
        assertNotNull(response.getBody());
    }

    @Test
    void returnsNotModifiedWhenEtagMatchesWithoutOpeningStream() {
        when(attachmentService.resolveAsset(11L, 31L, "hash", "original", 5L))
                .thenReturn(new DocumentAssetDescriptor("key", "image/png", 42L, "\"sha256:hash\""));

        var response = controller.asset(request("\"sha256:hash\""), 11L, 31L, "hash", "original");

        assertEquals(HttpStatus.NOT_MODIFIED, response.getStatusCode());
        assertEquals("\"sha256:hash\"", response.getHeaders().getETag());
        verify(attachmentService, never()).openAssetStream(any());
    }

    @Test
    void returnsServiceUnavailableWhenStorageDisabled() {
        storageProperties.setEnabled(false);
        controller = new DocumentAssetController(attachmentService, storageProperties);

        var response = controller.asset(request(null), 11L, 31L, "hash", "original");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verify(attachmentService, never()).resolveAsset(any(), any(), any(), any(), any());
    }

    private MockHttpServletRequest request(String ifNoneMatch) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/document-assets/11/31/hash/original");
        request.setAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID, 5L);
        if (ifNoneMatch != null) {
            request.addHeader(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        return request;
    }
}
