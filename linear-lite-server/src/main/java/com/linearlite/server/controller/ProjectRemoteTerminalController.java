package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.RemoteTerminalDtos.AuditRecord;
import com.linearlite.server.dto.RemoteTerminalDtos.CaView;
import com.linearlite.server.dto.RemoteTerminalDtos.CertificateRecord;
import com.linearlite.server.dto.RemoteTerminalDtos.GrantsRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.Overview;
import com.linearlite.server.dto.RemoteTerminalDtos.ProfileRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.ProfileView;
import com.linearlite.server.dto.RemoteTerminalDtos.TargetRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.TargetView;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.RemoteTerminalAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 远程终端团队配置管理，仅项目创建者可用；使用网页登录态。 */
@RestController
@RequestMapping("/api/projects/{projectId}/remote-terminal")
public class ProjectRemoteTerminalController {

    private final RemoteTerminalAdminService service;

    public ProjectRemoteTerminalController(RemoteTerminalAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Overview>> overview(HttpServletRequest request, @PathVariable Long projectId) {
        return ok(service.overview(projectId, userId(request)));
    }

    @PostMapping("/ca")
    public ResponseEntity<ApiResponse<CaView>> createCa(HttpServletRequest request, @PathVariable Long projectId) {
        return ok(service.createCa(projectId, userId(request)));
    }

    @PostMapping("/ca/rotate")
    public ResponseEntity<ApiResponse<CaView>> rotateCa(HttpServletRequest request, @PathVariable Long projectId) {
        return ok(service.rotateCa(projectId, userId(request)));
    }

    @PostMapping("/ca/{caId}/retire")
    public ResponseEntity<ApiResponse<Void>> retireCa(HttpServletRequest request, @PathVariable Long projectId,
                                                      @PathVariable Long caId) {
        service.retireCa(projectId, caId, userId(request));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/targets")
    public ResponseEntity<ApiResponse<TargetView>> createTarget(HttpServletRequest request,
                                                                @PathVariable Long projectId,
                                                                @RequestBody TargetRequest body) {
        return ok(service.createTarget(projectId, userId(request), body));
    }

    @PutMapping("/targets/{targetRef}")
    public ResponseEntity<ApiResponse<TargetView>> updateTarget(HttpServletRequest request,
                                                                @PathVariable Long projectId,
                                                                @PathVariable Long targetRef,
                                                                @RequestBody TargetRequest body) {
        return ok(service.updateTarget(projectId, targetRef, userId(request), body));
    }

    @DeleteMapping("/targets/{targetRef}")
    public ResponseEntity<ApiResponse<Void>> deleteTarget(HttpServletRequest request, @PathVariable Long projectId,
                                                          @PathVariable Long targetRef) {
        service.deleteTarget(projectId, targetRef, userId(request));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping(value = "/targets/{targetRef}/access-script", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> accessScript(HttpServletRequest request, @PathVariable Long projectId,
                                               @PathVariable Long targetRef) {
        String script = service.accessScript(projectId, targetRef, userId(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("linear-lite-access.sh", StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(script);
    }

    @PostMapping("/targets/{targetRef}/profiles")
    public ResponseEntity<ApiResponse<ProfileView>> createProfile(HttpServletRequest request,
                                                                  @PathVariable Long projectId,
                                                                  @PathVariable Long targetRef,
                                                                  @RequestBody ProfileRequest body) {
        return ok(service.createProfile(projectId, targetRef, userId(request), body));
    }

    @PutMapping("/profiles/{profileRef}")
    public ResponseEntity<ApiResponse<ProfileView>> updateProfile(HttpServletRequest request,
                                                                  @PathVariable Long projectId,
                                                                  @PathVariable Long profileRef,
                                                                  @RequestBody ProfileRequest body) {
        return ok(service.updateProfile(projectId, profileRef, userId(request), body));
    }

    @DeleteMapping("/profiles/{profileRef}")
    public ResponseEntity<ApiResponse<Void>> deleteProfile(HttpServletRequest request, @PathVariable Long projectId,
                                                           @PathVariable Long profileRef) {
        service.deleteProfile(projectId, profileRef, userId(request));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PutMapping("/profiles/{profileRef}/grants")
    public ResponseEntity<ApiResponse<List<Long>>> setGrants(HttpServletRequest request,
                                                             @PathVariable Long projectId,
                                                             @PathVariable Long profileRef,
                                                             @RequestBody GrantsRequest body) {
        return ok(service.setGrants(projectId, profileRef, userId(request), body == null ? null : body.userIds()));
    }

    @GetMapping("/certificates")
    public ResponseEntity<ApiResponse<List<CertificateRecord>>> certificates(
            HttpServletRequest request, @PathVariable Long projectId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ok(service.certificates(projectId, userId(request), limit));
    }

    @GetMapping("/audit")
    public ResponseEntity<ApiResponse<List<AuditRecord>>> audit(
            HttpServletRequest request, @PathVariable Long projectId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ok(service.auditEvents(projectId, userId(request), limit));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
    }
}
