package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.ExternalAssignee;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.User;
import com.linearlite.server.mapper.ExternalAssigneeMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssigneeServiceTest {
    @Mock UserMapper userMapper;
    @Mock ProjectMemberMapper projectMemberMapper;
    @Mock ExternalAssigneeMapper externalAssigneeMapper;
    private AssigneeService service;

    @BeforeEach
    void setUp() {
        service = new AssigneeService(userMapper, projectMemberMapper, externalAssigneeMapper);
    }

    @Test
    void validatesMemberAndExternalKeysInTheirOwnNamespaces() {
        User user = new User();
        user.setId(7L);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        assertEquals("user:7", service.requireProjectAssignee(3L, "user:7"));

        ExternalAssignee external = new ExternalAssignee();
        external.setId(7L);
        external.setProjectId(3L);
        external.setDisplayName("Sam");
        when(externalAssigneeMapper.selectById(7L)).thenReturn(external);
        assertEquals("external:7", service.requireProjectAssignee(3L, "external:7"));
        assertThrows(IllegalArgumentException.class, () -> service.requireProjectAssignee(4L, "external:7"));
        assertThrows(IllegalArgumentException.class, () -> service.requireProjectAssignee(3L, "7"));
    }

    @Test
    void keepsSameNamedMemberAndExternalPersonAsDistinctOptions() {
        when(projectMemberMapper.selectAssignees(3L)).thenReturn(
                List.of(new ProjectMemberMapper.MemberAssigneeRow(7L, "Sam", null, "human")));
        ExternalAssignee external = new ExternalAssignee();
        external.setId(7L);
        external.setProjectId(3L);
        external.setDisplayName("Sam");
        when(externalAssigneeMapper.selectByProject(3L)).thenReturn(List.of(external));

        var options = service.listProjectAssignees(3L);

        assertEquals(List.of("user:7", "external:7"), options.stream().map(option -> option.getKey()).toList());
        assertEquals(List.of("Sam", "Sam"), options.stream().map(option -> option.getName()).toList());
    }

    @Test
    void resolvesEqualNumericIdsByTheirCanonicalKeys() {
        User user = new User();
        user.setId(7L);
        user.setUsername("Member Sam");
        when(userMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(user));
        ExternalAssignee external = new ExternalAssignee();
        external.setId(7L);
        external.setProjectId(3L);
        external.setDisplayName("External Sam");
        when(externalAssigneeMapper.selectByProjectAndIds(3L, List.of(7L))).thenReturn(List.of(external));

        Task memberTask = task("user:7", 3L);
        Task externalTask = task("external:7", 3L);
        service.fillTaskNames(List.of(memberTask, externalTask), 3L);

        assertEquals("Member Sam", memberTask.getAssigneeName());
        assertEquals("External Sam", externalTask.getAssigneeName());
    }

    @Test
    void rejectsInvalidOrUnresolvableNonNullTaskKeys() {
        assertThrows(IllegalStateException.class,
                () -> service.fillTaskNames(List.of(task("7", 3L)), 3L));
        assertThrows(IllegalStateException.class,
                () -> service.fillTaskNames(List.of(task("user:7", 3L)), 3L));
    }

    private static Task task(String key, Long projectId) {
        Task task = new Task();
        task.setAssigneeKey(key);
        task.setProjectId(projectId);
        return task;
    }
}
