package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.AssigneeOption;
import com.linearlite.server.entity.ExternalAssignee;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.User;
import com.linearlite.server.mapper.ExternalAssigneeMapper;
import com.linearlite.server.mapper.ProjectMemberMapper.MemberAssigneeRow;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AssigneeService {
    private static final Pattern KEY = Pattern.compile("^(user|external):([1-9][0-9]*)$");
    private final UserMapper userMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ExternalAssigneeMapper externalAssigneeMapper;

    public AssigneeService(UserMapper userMapper, ProjectMemberMapper projectMemberMapper,
                           ExternalAssigneeMapper externalAssigneeMapper) {
        this.userMapper = userMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.externalAssigneeMapper = externalAssigneeMapper;
    }

    public List<AssigneeOption> listProjectAssignees(Long projectId) {
        List<AssigneeOption> options = new ArrayList<>();
        for (MemberAssigneeRow row : projectMemberMapper.selectAssignees(projectId)) {
            options.add(new AssigneeOption(userKey(row.userId()), row.username(), row.avatarUrl(), row.principalType()));
        }
        for (ExternalAssignee row : externalAssigneeMapper.selectByProject(projectId)) {
            options.add(new AssigneeOption(externalKey(row.getId()), row.getDisplayName(), null, "external"));
        }
        return options;
    }

    public void fillTaskNames(List<? extends AssigneeTask> tasks, Long projectId) {
        if (tasks == null || tasks.isEmpty()) return;
        List<Long> userIds = new ArrayList<>();
        List<Long> externalIds = new ArrayList<>();
        for (AssigneeTask task : tasks) {
            KeyParts key = parseTaskKey(task.getAssigneeKey());
            if (key == null) continue;
            (key.kind.equals("user") ? userIds : externalIds).add(key.id);
        }
        Map<String, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, userIds))
                    .forEach(user -> names.put(userKey(user.getId()), user.getUsername()));
        }
        if (!externalIds.isEmpty() && projectId != null) externalNames(projectId, externalIds, names);
        for (AssigneeTask task : tasks) {
            String key = task.getAssigneeKey();
            if (key == null) {
                task.setAssigneeName(null);
                continue;
            }
            parseTaskKey(key);
            String name = names.get(key);
            if (name == null) throw new IllegalStateException("负责人不存在或不属于当前项目: " + key);
            task.setAssigneeName(name);
        }
    }

    public void fillTaskNamesByProject(List<? extends AssigneeTask> tasks) {
        Map<Long, List<AssigneeTask>> tasksByProject = new HashMap<>();
        for (AssigneeTask task : tasks) {
            if (task instanceof TaskProjectAssignee projectTask && projectTask.getProjectId() != null) {
                tasksByProject.computeIfAbsent(projectTask.getProjectId(), ignored -> new ArrayList<>()).add(task);
            }
        }
        tasksByProject.forEach((projectId, projectTasks) -> fillTaskNames(projectTasks, projectId));
    }

    private void externalNames(Long projectId, List<Long> externalIds, Map<String, String> names) {
        externalAssigneeMapper.selectByProjectAndIds(projectId, externalIds)
                .forEach(external -> names.put(externalKey(external.getId()), external.getDisplayName()));
    }

    public String requireProjectAssignee(Long projectId, String key) {
        KeyParts parts = parse(key);
        if (parts == null) throw new IllegalArgumentException("assigneeKey 格式无效");
        if (parts.kind.equals("user")) {
            User user = userMapper.selectById(parts.id);
            if (user == null || projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                    .eq(ProjectMember::getProjectId, projectId).eq(ProjectMember::getUserId, parts.id)) == 0) {
                throw new IllegalArgumentException("负责人不是当前项目成员");
            }
            return key;
        }
        ExternalAssignee external = externalAssigneeMapper.selectById(parts.id);
        if (external == null || !projectId.equals(external.getProjectId())) {
            throw new IllegalArgumentException("外部负责人不属于当前项目");
        }
        return key;
    }

    @Transactional
    public String registerExternal(Long projectId, String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > 128) throw new IllegalArgumentException("外部负责人名称无效");
        ExternalAssignee existing = externalAssigneeMapper.selectByProjectAndName(projectId, name);
        if (existing != null) return externalKey(existing.getId());
        ExternalAssignee external = new ExternalAssignee();
        external.setProjectId(projectId);
        external.setDisplayName(name);
        try {
            externalAssigneeMapper.insert(external);
        } catch (DuplicateKeyException ignored) {
            ExternalAssignee winner = externalAssigneeMapper.selectByProjectAndName(projectId, name);
            if (winner == null) throw ignored;
            return externalKey(winner.getId());
        }
        return externalKey(external.getId());
    }

    public static String userKey(Long userId) { return userId == null ? null : "user:" + userId; }
    public static String externalKey(Long id) { return "external:" + id; }

    /** 从负责人键解析站内消息接收人；外部负责人没有站内账号。 */
    public static Long userId(String key) {
        KeyParts parts = parseTaskKey(key);
        return parts != null && parts.kind.equals("user") ? parts.id : null;
    }

    private static KeyParts parse(String key) {
        if (key == null) return null;
        Matcher matcher = KEY.matcher(key);
        if (!matcher.matches()) return null;
        try { return new KeyParts(matcher.group(1), Long.parseLong(matcher.group(2))); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static KeyParts parseTaskKey(String key) {
        KeyParts parts = parse(key);
        if (key != null && parts == null) throw new IllegalStateException("负责人键格式无效: " + key);
        return parts;
    }

    private record KeyParts(String kind, Long id) {}

    public interface AssigneeTask {
        String getAssigneeKey();
        void setAssigneeName(String name);
    }

    public interface TaskProjectAssignee extends AssigneeTask {
        Long getProjectId();
    }
}
