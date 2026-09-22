package com.linearlite.server.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeInstallerControllerTest {
    private final BridgeInstallerController controller = new BridgeInstallerController();

    @Test
    void packagesMacInstallerWithBridgeRuntime() throws Exception {
        ResponseEntity<byte[]> response = controller.download("darwin");

        assertEquals(200, response.getStatusCode().value());
        Set<String> entries = entries(response.getBody());
        assertTrue(entries.contains("pi-bridge/install/macos/install.command"));
        assertTrue(entries.contains("pi-bridge/scripts/service.mjs"));
        assertTrue(entries.contains("pi-bridge/src/index.mjs"));
    }

    @Test
    void packagesWindowsInstallerWithBridgeRuntime() throws Exception {
        ResponseEntity<byte[]> response = controller.download("win32");

        assertEquals(200, response.getStatusCode().value());
        Set<String> entries = entries(response.getBody());
        assertTrue(entries.contains("pi-bridge/install/windows/install.ps1"));
        assertTrue(entries.contains("pi-bridge/scripts/service.mjs"));
        assertTrue(entries.contains("pi-bridge/INSTALL.txt"));
        String installer = entryText(response.getBody(), "pi-bridge/install/windows/install.ps1");
        assertTrue(installer.chars().allMatch((character) -> character < 128),
                "Windows PowerShell 5.1 installer must use an encoding-safe character set");
    }

    private Set<String> entries(byte[] archive) throws Exception {
        Set<String> entries = new HashSet<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) entries.add(entry.getName());
        }
        return entries;
    }

    private String entryText(byte[] archive, String name) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalArgumentException("Missing ZIP entry: " + name);
    }
}
