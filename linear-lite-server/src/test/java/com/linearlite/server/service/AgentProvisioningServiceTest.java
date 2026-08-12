package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.User;
import com.linearlite.server.mapper.AgentCredentialMapper;
import com.linearlite.server.mapper.ProjectAgentBindingMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentProvisioningServiceTest {
    private final UserMapper userMapper = mock(UserMapper.class);
    private final AgentCredentialMapper credentialMapper = mock(AgentCredentialMapper.class);
    private final ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
    private final ProjectAgentBindingMapper bindingMapper = mock(ProjectAgentBindingMapper.class);
    private final ProjectAccessGuard projectAccessGuard = mock(ProjectAccessGuard.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AgentProvisioningService service = new AgentProvisioningService(
            userMapper, credentialMapper, memberMapper, bindingMapper, projectAccessGuard, passwordEncoder);

    @Test
    void reportsNotConfiguredWhenPiAgentDoesNotExist() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertFalse(service.getStatus(6L, 7L).configured());
    }

    @Test
    void reportsConfiguredWhenEnabledProjectBindingExists() {
        User agent = new User();
        agent.setId(37L);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(agent);
        when(bindingMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertTrue(service.getStatus(6L, 7L).configured());
    }
}
