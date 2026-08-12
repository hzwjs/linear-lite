package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.linearlite.server.dto.ConfigurePiAgentResponse;
import com.linearlite.server.dto.PiAgentStatusResponse;
import com.linearlite.server.entity.AgentCredential;
import com.linearlite.server.entity.ProjectAgentBinding;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.User;
import com.linearlite.server.mapper.AgentCredentialMapper;
import com.linearlite.server.mapper.ProjectAgentBindingMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AgentProvisioningService {
    private final UserMapper userMapper;
    private final AgentCredentialMapper credentialMapper;
    private final ProjectMemberMapper memberMapper;
    private final ProjectAgentBindingMapper bindingMapper;
    private final ProjectAccessGuard projectAccessGuard;
    private final PasswordEncoder passwordEncoder;

    public AgentProvisioningService(UserMapper userMapper, AgentCredentialMapper credentialMapper,
                                    ProjectMemberMapper memberMapper, ProjectAgentBindingMapper bindingMapper,
                                    ProjectAccessGuard projectAccessGuard, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.credentialMapper = credentialMapper;
        this.memberMapper = memberMapper;
        this.bindingMapper = bindingMapper;
        this.projectAccessGuard = projectAccessGuard;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(rollbackFor = Exception.class)
    public ConfigurePiAgentResponse configurePi(Long projectId, Long operatorId) {
        projectAccessGuard.requireOwner(projectId, operatorId);
        User agent = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPrincipalType, "agent")
                .eq(User::getAgentKey, AgentTaskOrchestrationService.PI_AGENT_KEY));
        if (agent == null) {
            agent = new User();
            agent.setUsername("Pi");
            agent.setEmail("pi@agent.local");
            agent.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            agent.setPrincipalType("agent");
            agent.setAgentKey(AgentTaskOrchestrationService.PI_AGENT_KEY);
            agent.setEnabled(true);
            userMapper.insert(agent);
        }
        ProjectMember member = memberMapper.selectOne(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, agent.getId()));
        if (member == null) {
            member = new ProjectMember();
            member.setProjectId(projectId);
            member.setUserId(agent.getId());
            member.setRole("agent");
            memberMapper.insert(member);
        }
        ProjectAgentBinding binding = bindingMapper.selectOne(new LambdaQueryWrapper<ProjectAgentBinding>()
                .eq(ProjectAgentBinding::getProjectId, projectId)
                .eq(ProjectAgentBinding::getAgentUserId, agent.getId()));
        if (binding == null) {
            binding = new ProjectAgentBinding();
            binding.setProjectId(projectId);
            binding.setAgentUserId(agent.getId());
            binding.setEnabled(true);
            bindingMapper.insert(binding);
        } else {
            binding.setEnabled(true);
            bindingMapper.updateById(binding);
        }
        credentialMapper.update(null, new UpdateWrapper<AgentCredential>()
                .eq("agent_user_id", agent.getId()).set("enabled", false));
        String token = "pi_" + UUID.randomUUID().toString().replace("-", "");
        AgentCredential credential = new AgentCredential();
        credential.setAgentUserId(agent.getId());
        credential.setTokenHash(AgentAuthenticationService.hash(token));
        credential.setEnabled(true);
        credential.setCreatedAt(LocalDateTime.now());
        credentialMapper.insert(credential);
        return new ConfigurePiAgentResponse(agent.getId(), agent.getAgentKey(), token);
    }

    @Transactional(readOnly = true)
    public PiAgentStatusResponse getStatus(Long projectId, Long operatorId) {
        projectAccessGuard.requireOwner(projectId, operatorId);
        User agent = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPrincipalType, "agent")
                .eq(User::getAgentKey, AgentTaskOrchestrationService.PI_AGENT_KEY));
        if (agent == null) {
            return new PiAgentStatusResponse(false);
        }
        Long bindingCount = bindingMapper.selectCount(new LambdaQueryWrapper<ProjectAgentBinding>()
                .eq(ProjectAgentBinding::getProjectId, projectId)
                .eq(ProjectAgentBinding::getAgentUserId, agent.getId())
                .eq(ProjectAgentBinding::getEnabled, true));
        return new PiAgentStatusResponse(bindingCount != null && bindingCount > 0);
    }
}
