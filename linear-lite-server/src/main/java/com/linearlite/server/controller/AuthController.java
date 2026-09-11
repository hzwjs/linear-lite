package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.dto.LoginRequest;
import com.linearlite.server.dto.LoginResponse;
import com.linearlite.server.dto.RegisterRequest;
import com.linearlite.server.dto.ResetPasswordRequest;
import com.linearlite.server.dto.SendPasswordResetCodeRequest;
import com.linearlite.server.dto.SendRegisterCodeRequest;
import com.linearlite.server.service.AuthService;
import com.linearlite.server.util.DocumentAssetCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final JwtProperties jwtProperties;

    public AuthController(AuthService authService, JwtProperties jwtProperties) {
        this.authService = authService;
        this.jwtProperties = jwtProperties;
    }

    /**
     * 登录：校验邮箱/用户名、密码，成功返回 JWT（及 userId、username）。
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @RequestBody LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        LoginResponse login = authService.login(request.getIdentity(), request.getPassword());
        writeDocumentAssetCookie(httpRequest, httpResponse, login);
        return ResponseEntity.ok(ApiResponse.success(login));
    }

    @PostMapping("/register/send-code")
    public ResponseEntity<ApiResponse<Void>> sendRegisterCode(@RequestBody SendRegisterCodeRequest request) {
        authService.sendRegisterCode(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<LoginResponse>> register(
            @RequestBody RegisterRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        LoginResponse login = authService.register(request);
        writeDocumentAssetCookie(httpRequest, httpResponse, login);
        return ResponseEntity.ok(ApiResponse.success(login));
    }

    /** 清空文档图片资源 Cookie；不依赖鉴权，保证令牌失效后也能清理本机凭证。 */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request, HttpServletResponse response) {
        DocumentAssetCookie.clear(response, DocumentAssetCookie.isSecureRequest(request));
        return ResponseEntity.ok(ApiResponse.success());
    }

    /** 为已登录会话补发文档图片资源 Cookie，覆盖 Cookie 过期或升级前已有的本地会话。 */
    @PostMapping("/document-asset-session")
    public ResponseEntity<ApiResponse<Void>> documentAssetSession(
            HttpServletRequest request, HttpServletResponse response) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            if (!token.isEmpty()) {
                DocumentAssetCookie.write(
                        response, token, jwtProperties.getExpirationMs() / 1000,
                        DocumentAssetCookie.isSecureRequest(request));
            }
        }
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/password-reset/send-code")
    public ResponseEntity<ApiResponse<Void>> sendPasswordResetCode(@RequestBody SendPasswordResetCodeRequest request) {
        authService.sendPasswordResetCode(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/password-reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    private void writeDocumentAssetCookie(
            HttpServletRequest request, HttpServletResponse response, LoginResponse login) {
        if (login == null || login.getToken() == null || login.getToken().isBlank()) {
            return;
        }
        DocumentAssetCookie.write(
                response, login.getToken(), jwtProperties.getExpirationMs() / 1000,
                DocumentAssetCookie.isSecureRequest(request));
    }
}
