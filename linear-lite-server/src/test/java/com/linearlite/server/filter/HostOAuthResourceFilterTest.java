package com.linearlite.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.security.OAuthResourcePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostOAuthResourceFilterTest {
    @Test
    void authorizationRequiresHostResourceAndS256WithoutDuplicateResource() throws Exception {
        McpOAuthProperties properties = new McpOAuthProperties();
        properties.setEnabled(true);
        properties.setIssuer("https://example.test");
        properties.setResource("https://example.test/mcp");
        McpOAuthResourceParameterFilter filter = new McpOAuthResourceParameterFilter(properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.addParameter("scope", OAuthResourcePolicy.HOST_SCOPE);
        request.addParameter("resource", OAuthResourcePolicy.hostResource(properties));
        request.addParameter("code_challenge_method", "S256");
        assertEquals(200, execute(filter, request));

        MockHttpServletRequest plain = new MockHttpServletRequest("GET", "/oauth2/authorize");
        plain.addParameter("scope", OAuthResourcePolicy.HOST_SCOPE);
        plain.addParameter("resource", OAuthResourcePolicy.hostResource(properties));
        plain.addParameter("code_challenge_method", "plain");
        assertEquals(400, execute(filter, plain));

        MockHttpServletRequest mismatch = new MockHttpServletRequest("GET", "/oauth2/authorize");
        mismatch.addParameter("scope", OAuthResourcePolicy.HOST_SCOPE);
        mismatch.addParameter("resource", properties.getResource());
        mismatch.addParameter("code_challenge_method", "S256");
        assertEquals(400, execute(filter, mismatch));

        MockHttpServletRequest duplicate = new MockHttpServletRequest("GET", "/oauth2/authorize");
        duplicate.addParameter("scope", OAuthResourcePolicy.HOST_SCOPE);
        duplicate.addParameter("resource", OAuthResourcePolicy.hostResource(properties), properties.getResource());
        duplicate.addParameter("code_challenge_method", "S256");
        assertEquals(400, execute(filter, duplicate));
    }

    private static int execute(McpOAuthResourceParameterFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
