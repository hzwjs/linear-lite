package com.linearlite.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.common.HostProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HostProtocolFilterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HostProtocolFilter filter = new HostProtocolFilter(json);

    @Test void rejectsLegacyAndUnknownVersionsBeforeTheChain() throws Exception {
        for (String path : List.of("/api/collaboration-host/heartbeat", "/api/collaboration-host/recovery/read",
                "/api/collaboration-host/v2/batches/accept", "/api/collaboration-host/v4/batches/accept",
                "/api/collaboration-host/v3-other/tasks/claim")) {
            var request = new MockHttpServletRequest("POST", path);
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertEquals(426, response.getStatus(), path);
            assertEquals("3", response.getHeader(HostProtocol.HEADER), path);
            assertEquals("no-store", response.getHeader("Cache-Control"), path);
            assertEquals(426, json.readTree(response.getContentAsString()).path("code").asInt(), path);
            assertEquals(HostProtocol.UPGRADE_MESSAGE, json.readTree(response.getContentAsString()).path("message").asText(), path);
            assertNull(chain.getRequest(), path);
        }
    }

    @Test void currentVersionPassesAndTagsTheResponse() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/collaboration-host/v3/recovery/read");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertSame(request, chain.getRequest());
        assertEquals("3", response.getHeader(HostProtocol.HEADER));
    }

    @Test void otherResourcesAndCorsPreflightReachTheRestOfTheFilterChain() throws Exception {
        for (var request : List.of(new MockHttpServletRequest("GET", "/api/collaboration/host-installer"),
                new MockHttpServletRequest("GET", "/mcp"),
                new MockHttpServletRequest("POST", "/api/collaboration/batches/start"),
                new MockHttpServletRequest("OPTIONS", "/api/collaboration-host/v3/batches/pending"))) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertSame(request, chain.getRequest());
            assertNull(response.getHeader(HostProtocol.HEADER));
        }
    }
}
