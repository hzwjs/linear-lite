package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.codex.CodexDtos;
import com.linearlite.server.entity.CodexRunner;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.CodexDispatchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.linearlite.server.entity.CodexRepository;

@RestController @RequestMapping("/api/codex-runners")
public class CodexRunnerController {
 private final CodexDispatchService service; public CodexRunnerController(CodexDispatchService service){this.service=service;}
 @PostMapping("/enrollment-codes") public ResponseEntity<ApiResponse<CodexDtos.EnrollmentCodeResponse>> code(HttpServletRequest r){return ResponseEntity.ok(ApiResponse.success(service.createEnrollmentCode(user(r))));}
 @GetMapping public ResponseEntity<ApiResponse<List<CodexRunner>>> list(HttpServletRequest r){return ResponseEntity.ok(ApiResponse.success(service.listRunners(user(r))));}
 @GetMapping("/{runnerId}/repositories") public ResponseEntity<ApiResponse<List<CodexRepository>>> repositories(HttpServletRequest r,@PathVariable Long runnerId){return ResponseEntity.ok(ApiResponse.success(service.listRepositories(runnerId,user(r))));}
 @DeleteMapping("/{runnerId}") public ResponseEntity<ApiResponse<Void>> revoke(HttpServletRequest r,@PathVariable Long runnerId){service.revokeRunner(runnerId,user(r));return ResponseEntity.ok(ApiResponse.success());}
 private Long user(HttpServletRequest r){return (Long)r.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);}
}
