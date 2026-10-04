package com.linearlite.server.service;

import com.linearlite.server.exception.ForbiddenOperationException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.HttpVersion;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentRemoteImageServiceTest {
    private final ProjectDocumentAttachmentService attachments = mock(ProjectDocumentAttachmentService.class);
    private final DocumentRemoteImageService service = new DocumentRemoteImageService(attachments);

    @Test
    void permissionFailureHappensBeforeNetworkAccess() {
        doThrow(new ForbiddenOperationException("无权限")).when(attachments).requireDocument(11L, 5L);
        assertThrows(ForbiddenOperationException.class, () -> service.importImage(11L, "not-a-url", 5L));
        verify(attachments, never()).uploadImage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void routesDownloadedBytesThroughTheExistingImageAttachmentService() throws Exception {
        byte[] bytes = png();
        var spy = spy(service);
        doReturn(new DocumentRemoteImageService.RemoteImage("image.png", "image/png", bytes))
                .when(spy).download("https://image.example/a.png");
        spy.importImage(11L, "https://image.example/a.png", 5L);
        var ordered = inOrder(attachments);
        ordered.verify(attachments).requireDocument(11L, 5L);
        ordered.verify(attachments).uploadImage(11L, "image.png", "image/png", bytes, null, 5L);
    }

    @Test
    void blocksPrivateReservedAndTunnelAddressesIncludingIpv6() throws Exception {
        for (String host : new String[]{"0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254",
                "172.16.0.1", "192.168.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
                "224.0.0.1", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "2001:db8::1", "2002:7f00:1::1", "2001::1"}) {
            assertFalse(DocumentRemoteImageService.isPublicAddress(InetAddress.getByName(host)), host);
        }
        assertTrue(DocumentRemoteImageService.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(DocumentRemoteImageService.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void rejectsLocalProtocolsCredentialsAndNonWebPorts() {
        for (String url : new String[]{"file:///tmp/a.png", "data:image/png;base64,abc", "http://user:pass@8.8.8.8/a", "http://8.8.8.8:22/a", "//8.8.8.8/a"}) {
            assertThrows(IllegalArgumentException.class, () -> DocumentRemoteImageService.checkedUri(url), url);
        }
        assertThrows(IllegalArgumentException.class, () -> service.download("http://127.0.0.1/image"));
    }

    @Test
    void readsTheActualImageFormatRatherThanTrustingTheRemoteContentType() throws Exception {
        var client = mock(CloseableHttpClient.class);
        var response = response(200, png());
        when(client.execute(any(HttpUriRequest.class))).thenReturn(response);
        var image = service.download("https://8.8.8.8/image", client);
        assertEquals("image/png", image.contentType());
        assertArrayEquals(png(), image.bytes());
    }

    @Test
    void blocksRedirectsIntoTheLocalNetworkBeforeTheSecondRequest() throws Exception {
        var client = mock(CloseableHttpClient.class);
        var redirect = response(302, new byte[0]);
        when(redirect.getFirstHeader("Location")).thenReturn(new BasicHeader("Location", "http://169.254.169.254/latest/meta-data"));
        when(client.execute(any(HttpUriRequest.class))).thenReturn(redirect);
        assertThrows(IllegalArgumentException.class, () -> service.download("https://8.8.8.8/image", client));
        verify(client, times(1)).execute(any(HttpUriRequest.class));
    }

    @Test
    void capsRedirectsAndRejectsNonImagesAndOversizedChunkedBodies() throws Exception {
        var client = mock(CloseableHttpClient.class);
        var redirect = response(302, new byte[0]);
        when(redirect.getFirstHeader("Location")).thenReturn(new BasicHeader("Location", "/loop"));
        when(client.execute(any(HttpUriRequest.class))).thenReturn(redirect);
        assertThrows(IllegalArgumentException.class, () -> service.download("https://8.8.8.8/image", client));
        verify(client, times(4)).execute(any(HttpUriRequest.class));
        assertThrows(IllegalArgumentException.class, () -> DocumentRemoteImageService.imageContentType("<html>not an image</html>".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> DocumentRemoteImageService.readImage(
                new ByteArrayInputStream(new byte[DocumentRemoteImageService.MAX_BYTES + 1]),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10)));
        assertThrows(IllegalArgumentException.class, () -> DocumentRemoteImageService.readImage(
                new ByteArrayInputStream(png()), System.nanoTime() - 1));
    }

    static byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private static CloseableHttpResponse response(int status, byte[] bytes) {
        var response = mock(CloseableHttpResponse.class);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, status, "test"));
        when(response.getEntity()).thenReturn(new ByteArrayEntity(bytes));
        return response;
    }
}
