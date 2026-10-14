package com.linearlite.server.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.User;
import com.linearlite.server.mapper.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class LinearLiteUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    public LinearLiteUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String identity) throws UsernameNotFoundException {
        if (identity == null || identity.isBlank()) {
            throw new UsernameNotFoundException("Linear Lite account was not found.");
        }
        String normalizedIdentity = identity.trim();
        User user = null;
        if (normalizedIdentity.contains("@")) {
            user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getEmail, normalizedIdentity).last("LIMIT 1"));
        }
        if (user == null) {
            user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, normalizedIdentity).last("LIMIT 1"));
        }
        if (user == null || user.getId() == null || user.getPassword() == null
                || !"human".equals(user.getPrincipalType())) {
            throw new UsernameNotFoundException("Linear Lite account was not found.");
        }
        return new LinearLiteUserDetails(
                user.getId(), user.getUsername(), Boolean.TRUE.equals(user.getEnabled()));
    }
}
