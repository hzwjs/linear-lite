package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.common.HostProtocol;
import com.linearlite.server.dto.HostDeviceAuthorizationResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.HostDeviceAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class HostDeviceAuthorizationController {
    private final HostDeviceAuthorizationService authorizations;

    public HostDeviceAuthorizationController(HostDeviceAuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @GetMapping("/api/collaboration/hosts")
    public ResponseEntity<ApiResponse<List<HostDeviceAuthorizationResponse>>> list(HttpServletRequest request) {
        return noStore(ApiResponse.success(authorizations.list(userId(request))));
    }

    @DeleteMapping("/api/collaboration/hosts/{id}")
    public ResponseEntity<ApiResponse<Void>> revoke(HttpServletRequest request, @PathVariable Long id) {
        authorizations.revoke(userId(request), id);
        return noStore(ApiResponse.success());
    }

    @PostMapping(HostProtocol.API_PATH + "/heartbeat")
    public ResponseEntity<ApiResponse<HostDeviceAuthorizationResponse>> heartbeat(HttpServletRequest request) {
        return noStore(ApiResponse.success(authorizations.heartbeat(
                (String) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_HOST_AUTHORIZATION_ID), userId(request))));
    }

    private static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
    }

    private static <T> ResponseEntity<ApiResponse<T>> noStore(ApiResponse<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

}
