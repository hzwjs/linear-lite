package com.linearlite.server.controller;
import com.linearlite.server.common.ApiResponse; import com.linearlite.server.dto.codex.CodexDtos; import com.linearlite.server.entity.ProjectCodexBinding; import com.linearlite.server.filter.JwtAuthFilter; import com.linearlite.server.service.CodexDispatchService; import jakarta.servlet.http.HttpServletRequest; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/projects/{projectId}/codex-binding") public class CodexProjectBindingController {
 private final CodexDispatchService service; public CodexProjectBindingController(CodexDispatchService service){this.service=service;}
 @GetMapping public ResponseEntity<ApiResponse<ProjectCodexBinding>> get(HttpServletRequest r,@PathVariable Long projectId){return ResponseEntity.ok(ApiResponse.success(service.getBinding(projectId,user(r))));}
 @PutMapping public ResponseEntity<ApiResponse<ProjectCodexBinding>> put(HttpServletRequest r,@PathVariable Long projectId,@RequestBody CodexDtos.BindingRequest body){return ResponseEntity.ok(ApiResponse.success(service.saveBinding(projectId,user(r),body)));}
 private Long user(HttpServletRequest r){return (Long)r.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);}
}
