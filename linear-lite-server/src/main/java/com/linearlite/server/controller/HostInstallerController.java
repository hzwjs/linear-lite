package com.linearlite.server.controller;

import com.linearlite.server.filter.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 安装包随服务版本发布，经网页登录认证后按流下载。 */
@RestController
public class HostInstallerController {
    private final Resource installer;

    public HostInstallerController() {
        this(new ClassPathResource("host-distribution/Linear-Lite协作宿主-macOS-arm64-V3.1.0.dmg"));
    }

    HostInstallerController(Resource installer) { this.installer = installer; }

    @GetMapping("/api/collaboration/host-installer")
    public ResponseEntity<Resource> download(HttpServletRequest request) throws IOException {
        if (!(request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID) instanceof Long userId) || userId <= 0)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).build();
        if (!installer.exists() || !installer.isReadable())
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/x-apple-diskimage"))
                .contentLength(installer.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("linear-lite-host-macos-arm64-V3.1.0.dmg").build().toString())
                .body(installer);
    }
}
