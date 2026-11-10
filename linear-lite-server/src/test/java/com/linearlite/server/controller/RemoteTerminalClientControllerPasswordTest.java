package com.linearlite.server.controller;

import com.linearlite.server.dto.RemoteTerminalDtos.PasswordRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.PasswordResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.RemoteTerminalCertificateService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteTerminalClientControllerPasswordTest {
    @Test
    void passwordResponseUsesNoStoreAndAuthenticatedUser() {
        var service = mock(RemoteTerminalCertificateService.class);
        var request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L);
        var body = new PasswordRequest("ENG", "target", "profile", 60, 2);
        var responseBody = new PasswordResponse(UUID.randomUUID().toString(), false);
        when(service.password(7L, body)).thenReturn(responseBody);
        var response = new RemoteTerminalClientController(service).password(request, body);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(responseBody, response.getBody().getData());
        verify(service).password(7L, body);
    }
}
