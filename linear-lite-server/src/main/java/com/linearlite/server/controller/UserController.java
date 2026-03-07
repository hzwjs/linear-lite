package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.UserSummaryDto;
import com.linearlite.server.entity.User;
import com.linearlite.server.mapper.UserMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户 API：团队成员列表，供前端 Assignee 选择。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserMapper userMapper;

    public UserController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 获取团队成员列表。返回 id、username、avatar_url。
     * 需 JWT 认证。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserSummaryDto>>> list() {
        List<User> users = userMapper.selectList(null);
        List<UserSummaryDto> list = users.stream()
                .map(u -> new UserSummaryDto(u.getId(), u.getUsername(), u.getAvatarUrl()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(list));
    }
}
