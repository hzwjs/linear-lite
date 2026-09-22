package com.linearlite.server.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 提供与当前 Linear Lite 版本一致的 Bridge 源码安装包。 */
@RestController
@RequestMapping("/api/bridge/installers")
public class BridgeInstallerController {
    private static final Set<String> PLATFORMS = Set.of("darwin", "win32");
    private static final List<String> COMMON_FILES = List.of(
            "package.json",
            "scripts/service.mjs",
            "src/bridge-settings.mjs",
            "src/config-server.mjs",
            "src/event-stream.mjs",
            "src/index.mjs",
            "src/workspace-config.mjs"
    );

    @GetMapping("/{platform}")
    public ResponseEntity<byte[]> download(@PathVariable String platform) throws IOException {
        if (!PLATFORMS.contains(platform)) {
            return ResponseEntity.notFound().build();
        }
        byte[] archive = archive(platform);
        String filename = "linear-lite-pi-bridge-" + ("darwin".equals(platform) ? "macos" : "windows") + ".zip";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        headers.setContentLength(archive.length);
        return ResponseEntity.ok().headers(headers).body(archive);
    }

    private byte[] archive(String platform) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (String file : COMMON_FILES) addResource(zip, file);
            String installer = "darwin".equals(platform) ? "install/macos/install.command" : "install/windows/install.ps1";
            addResource(zip, installer);
            addText(zip, "INSTALL.txt", instructions(platform));
        }
        return output.toByteArray();
    }

    private void addResource(ZipOutputStream zip, String file) throws IOException {
        ClassPathResource resource = new ClassPathResource("bridge-distribution/" + file);
        if (!resource.exists()) throw new IOException("Bridge 安装资源缺失：" + file);
        zip.putNextEntry(new ZipEntry("pi-bridge/" + file));
        resource.getInputStream().transferTo(zip);
        zip.closeEntry();
    }

    private void addText(ZipOutputStream zip, String file, String content) throws IOException {
        zip.putNextEntry(new ZipEntry("pi-bridge/" + file));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String instructions(String platform) {
        if ("darwin".equals(platform)) {
            return "解压后打开终端，在 pi-bridge 目录执行：bash install/macos/install.command\n";
        }
        return "解压后打开 PowerShell，在 pi-bridge 目录执行：powershell -ExecutionPolicy Bypass -File install\\windows\\install.ps1\r\n";
    }
}
