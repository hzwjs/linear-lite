package com.linearlite.server.controller;

import com.linearlite.server.filter.JwtAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HostInstallerControllerTest {
    @Test void authenticatedDownloadStreamsThePackagedResource() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                new HostInstallerController(new ByteArrayResource(new byte[] {1, 2, 3}))).build();
        mvc.perform(get("/api/collaboration/host-installer").requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"linear-lite-host-macos-arm64-V3.1.0.dmg\""))
                .andExpect(content().contentType("application/x-apple-diskimage"))
                .andExpect(content().bytes(new byte[] {1, 2, 3}));
    }

    @Test void missingLoginCannotDownloadEvenWhenResourceExists() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                new HostInstallerController(new ByteArrayResource(new byte[] {1}))).build();
        mvc.perform(get("/api/collaboration/host-installer"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void missingDistributionDoesNotFallBackToAnotherVersion() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                new HostInstallerController(new ClassPathResource("host-distribution/missing-installer.dmg"))).build();
        mvc.perform(get("/api/collaboration/host-installer").requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"));
    }
}
