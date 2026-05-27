package com.linearlite.server.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WebConfigTest {

    @Test
    void corsAllowsViteFallbackLocalhostPorts() throws ServletException, IOException {
        WebConfig config = new WebConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173,http://127.0.0.1:5173");
        ReflectionTestUtils.setField(config, "allowedOriginPatterns", "http://localhost:*,http://127.0.0.1:*,http://[::1]:*");
        CorsFilter filter = config.corsFilter();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("Origin", "http://localhost:5174");
        request.addHeader("Content-Type", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNotEquals(403, response.getStatus());
    }
}
