package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.LoginRequest;
import com.linearlite.server.dto.LoginResponse;
import com.linearlite.server.dto.RegisterRequest;
import com.linearlite.server.dto.SendRegisterCodeRequest;
import com.linearlite.server.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权：登录接口，返回 JWT。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录：校验邮箱/用户名、密码，成功返回 JWT（及 userId、username）。
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                authService.login(request.getIdentity(), request.getPassword())
        ));
    }

    @PostMapping("/register/send-code")
    public ResponseEntity<ApiResponse<Void>> sendRegisterCode(@RequestBody SendRegisterCodeRequest request) {
        authService.sendRegisterCode(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<LoginResponse>> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.register(request)));
    }
}
