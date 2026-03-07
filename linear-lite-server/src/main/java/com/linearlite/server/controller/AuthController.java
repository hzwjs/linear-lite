package com.linearlite.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.LoginRequest;
import com.linearlite.server.dto.LoginResponse;
import com.linearlite.server.entity.User;
import com.linearlite.server.mapper.UserMapper;
import com.linearlite.server.util.JwtUtil;
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

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    public AuthController(UserMapper userMapper, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 登录：校验用户名、密码，成功返回 JWT（及 userId、username）。
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail(400, "用户名不能为空"));
        }
        LambdaQueryWrapper<User> q = new LambdaQueryWrapper<>();
        q.eq(User::getUsername, request.getUsername());
        User user = userMapper.selectOne(q);
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.fail(401, "用户名或密码错误"));
        }
        String inputPassword = request.getPassword() != null ? request.getPassword() : "";
        if (!inputPassword.equals(user.getPassword())) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.fail(401, "用户名或密码错误"));
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        LoginResponse data = new LoginResponse(token, user.getId(), user.getUsername());
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
