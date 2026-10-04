package com.linearlite.server.service;

import com.linearlite.server.dto.ProjectDocumentAttachmentResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 外链是导入输入；返回附件资源身份，正文不保存外链图片地址。 */
@Service
public class DocumentRemoteImageService {
    static final int MAX_BYTES = 6 * 1024 * 1024;
    static final int MAX_REDIRECTS = 3;
    private final ProjectDocumentAttachmentService attachments;

    public DocumentRemoteImageService(ProjectDocumentAttachmentService attachments) {
        this.attachments = attachments;
    }

    public ProjectDocumentAttachmentResponse importImage(Long documentId, String url, Long userId) {
        // 鉴权在网络访问前执行；上传时复核文档归属与成员关系。
        attachments.requireDocument(documentId, userId);
        RemoteImage image = download(url);
        return attachments.uploadImage(documentId, image.fileName(), image.contentType(), image.bytes(), null, userId);
    }

    RemoteImage download(String url) {
        RequestConfig config = RequestConfig.custom().setConnectTimeout(3000)
                .setSocketTimeout(3000).setConnectionRequestTimeout(3000).build();
        // DNS 在连接建立时复核，拒绝重绑定到内网；TLS 校验以原主机名执行。
        try (var client = HttpClients.custom().setDefaultRequestConfig(config)
                .setDnsResolver(DocumentRemoteImageService::publicAddresses)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build()) {
            return download(url, client);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取图片链接，请上传图片文件", e);
        }
    }

    RemoteImage download(String url, CloseableHttpClient client) {
        URI target = checkedUri(url);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                publicAddresses(target.getHost()); // IP 字面量也执行检查。
                HttpGet request = new HttpGet(target);
                request.setHeader("Accept", "image/png,image/jpeg,image/gif,image/webp,image/bmp");
                try (var response = client.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        var location = response.getFirstHeader("Location");
                        if (location == null || redirects == MAX_REDIRECTS) {
                            throw new IllegalArgumentException("图片链接重定向失败");
                        }
                        target = checkedUri(target.resolve(location.getValue()).toString());
                        checkDeadline(deadline);
                        continue;
                    }
                    if (status != 200 || response.getEntity() == null) {
                        throw new IllegalArgumentException("图片链接访问失败（HTTP " + status + "）");
                    }
                    if (response.getEntity().getContentLength() > MAX_BYTES) {
                        throw new IllegalArgumentException("外链图片超过 6 MB 限制");
                    }
                    byte[] bytes;
                    try (InputStream stream = response.getEntity().getContent()) {
                        bytes = readImage(stream, deadline);
                    }
                    String type = imageContentType(bytes);
                    String extension = switch (type) {
                        case "image/jpeg" -> "jpg";
                        case "image/webp" -> "webp";
                        case "image/gif" -> "gif";
                        case "image/bmp" -> "bmp";
                        default -> "png";
                    };
                    return new RemoteImage("粘贴图片." + extension, type, bytes);
                }
            }
            throw new IllegalArgumentException("图片链接重定向失败");
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("图片主机不可访问或指向本机、内网，请上传图片文件", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取图片链接，请上传图片文件", e);
        }
    }

    static URI checkedUri(String value) {
        try {
            if (value == null || value.length() > 8192) throw new IllegalArgumentException();
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片链接必须是公网 HTTP(S) 地址", e);
        }
    }

    static InetAddress[] publicAddresses(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) throw new UnknownHostException("图片链接不能指向本机或内网");
        }
        if (addresses.length == 0) throw new UnknownHostException("图片主机没有可用地址");
        return addresses;
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255;
        int b = bytes[1] & 255;
        if (bytes.length == 4) {
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && (b == 0 || b == 168))
                    || (a == 198 && (b == 18 || b == 19 || (b == 51 && (bytes[2] & 255) == 100)))
                    || (a == 203 && b == 0 && (bytes[2] & 255) == 113));
        }
        // IPv6 限定全球单播；排除文档、Teredo 和 6to4 隧道地址。
        return (a & 0xe0) == 0x20 && !(a == 0x20 && b == 2)
                && !(a == 0x20 && b == 1 && ((bytes[2] == 0 && bytes[3] == 0)
                || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)));
    }

    static byte[] readImage(InputStream stream, long deadline) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = stream.read(buffer)) != -1) {
            checkDeadline(deadline);
            if (output.size() + length > MAX_BYTES) throw new IllegalArgumentException("外链图片超过 6 MB 限制");
            output.write(buffer, 0, length);
        }
        checkDeadline(deadline);
        return output.toByteArray();
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() > deadline) throw new IllegalArgumentException("图片链接读取超时");
    }

    static String imageContentType(byte[] bytes) throws IOException {
        // WebP 没有 JDK 解码器；核对 RIFF 容器和图像块，作为原图资源使用。
        if (bytes.length >= 20 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")
                && (ascii(bytes, 12, "VP8 ") || ascii(bytes, 12, "VP8L") || ascii(bytes, 12, "VP8X"))) {
            return "image/webp";
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) throw new IOException("Invalid dimensions");
                    return switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                        case "png" -> "image/png";
                        case "jpeg", "jpg" -> "image/jpeg";
                        case "gif" -> "image/gif";
                        case "bmp" -> "image/bmp";
                        default -> throw new IllegalArgumentException("图片格式不支持，请上传 PNG、JPEG、GIF、BMP 或 WebP");
                    };
                } finally { reader.dispose(); }
            }
        }
        throw new IllegalArgumentException("链接返回的内容不是受支持的图片");
    }

    private static boolean ascii(byte[] bytes, int offset, String text) {
        for (int i = 0; i < text.length(); i++) if (bytes[offset + i] != text.charAt(i)) return false;
        return true;
    }

    record RemoteImage(String fileName, String contentType, byte[] bytes) { }
}
