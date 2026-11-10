package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.RemoteTerminalDtos.Catalog;
import com.linearlite.server.dto.RemoteTerminalDtos.CertificateRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.CertificateResponse;
import com.linearlite.server.dto.RemoteTerminalDtos.PasswordRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.PasswordResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.RemoteTerminalCertificateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 本机 MCP 使用：scope=remote-terminal 的 OAuth 令牌，读取已授权目录并申请短期证书。 */
@RestController
@RequestMapping("/api/remote-terminal")
public class RemoteTerminalClientController {

    private final RemoteTerminalCertificateService service;

    public RemoteTerminalClientController(RemoteTerminalCertificateService service) {
        this.service = service;
    }

    @GetMapping("/catalog")
    public ResponseEntity<ApiResponse<Catalog>> catalog(HttpServletRequest request,
                                                        @RequestParam("project") String project) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.catalog(project, userId(request))));
    }

    @PostMapping("/certificates")
    public ResponseEntity<ApiResponse<CertificateResponse>> issue(HttpServletRequest request,
                                                                  @RequestBody CertificateRequest body) {
        String clientId = (String) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_OAUTH_CLIENT_ID);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.issue(userId(request), request.getRemoteAddr(), clientId, body)));
    }

    @PostMapping("/passwords")
    public ResponseEntity<ApiResponse<PasswordResponse>> password(HttpServletRequest request,
                                                                   @RequestBody PasswordRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.password(userId(request), body)));
    }

    private static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
    }
}
