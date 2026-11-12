package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linearlite.server.dto.ProjectDeviceBindingResponse;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectDeviceBindingServiceTest {
    @Mock private ProjectDeviceBindingMapper bindings;
    @Mock private TaskExecutionAttemptMapper attempts;
    @Mock private ProjectMapper projects;
    @Mock private ProjectAccessGuard access;
    @Mock private CollaborationBatchMapper batches;
    private ProjectDeviceBindingService service;

    @BeforeAll
    static void initializeEntityMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "binding-test"),
                ProjectDeviceBinding.class);
    }

    @BeforeEach
    void setUp() {
        service = new ProjectDeviceBindingService(bindings, attempts, projects, access, batches);
    }

    @Test
    void listsActiveBindingsForAuthenticatedUserAndProject() {
        when(projects.lockProject(6L)).thenReturn(6L);
        ProjectDeviceBinding row = new ProjectDeviceBinding();
        row.setId(10L); row.setUserId(1L); row.setProjectId(6L); row.setDeviceId("device-A");
        row.setProjectDirectory("/work/a"); row.setRevision(1L);
        when(bindings.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> query = invocation.getArgument(0);
            assertTrue(query.getSqlSegment().contains("user_id"));
            assertTrue(query.getSqlSegment().contains("project_id"));
            assertTrue(query.getSqlSegment().contains("deleted_at IS NULL"));
            assertTrue(query.getParamNameValuePairs().containsValue(1L));
            assertTrue(query.getParamNameValuePairs().containsValue(6L));
            return List.of(row);
        });
        assertEquals("device-A", service.list(1L, 6L).get(0).deviceId());
        InOrder order = inOrder(projects, access, bindings);
        order.verify(projects).lockProject(6L);
        order.verify(access).requireMember(6L, 1L);
        order.verify(bindings).selectList(any());
    }

    @Test
    void createsUserProjectDeviceBindingAndReadsPersistedDirectory() {
        when(projects.lockProject(6L)).thenReturn(6L);
        AtomicReference<ProjectDeviceBinding> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            ProjectDeviceBinding binding = invocation.getArgument(0);
            binding.setId(10L);
            inserted.set(binding);
            return 1;
        }).when(bindings).insert(any(ProjectDeviceBinding.class));
        when(bindings.selectById(10L)).thenAnswer(invocation -> inserted.get());

        ProjectDeviceBindingResponse result = service.save(1L, 6L, "device-A", "/work/项目");

        assertEquals(10L, result.id());
        assertEquals(1L, result.userId());
        assertEquals(6L, result.projectId());
        assertEquals("device-A", result.deviceId());
        assertEquals("/work/项目", result.projectDirectory());
        assertEquals(1L, result.revision());
        InOrder order = inOrder(projects, access, bindings);
        order.verify(projects).lockProject(6L);
        order.verify(access).requireMember(6L, 1L);
        order.verify(bindings).selectForUpdate(1L, 6L, "device-A");
        order.verify(bindings).insert(any(ProjectDeviceBinding.class));
    }

    @Test
    void repeatedSaveOfSameDirectoryDoesNotChangeRevisionOrFrozenAttempt() {
        ProjectDeviceBinding current = binding(1L, "/work/a", null);
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(1L, 6L, "device-A")).thenReturn(current);

        ProjectDeviceBindingResponse result = service.save(1L, 6L, "device-A", "/work/a");

        assertEquals(1L, result.revision());
        verify(attempts, never()).selectCount(any());
        verify(bindings, never()).update(isNull(), any());
    }

    @Test
    void blocksDirectoryChangeAndDeletionWhileStopIsUnconfirmed() throws NoSuchMethodException {
        ProjectDeviceBinding current = binding(1L, "/work/a", null);
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(1L, 6L, "device-A")).thenReturn(current);
        when(attempts.selectCount(any())).thenReturn(1L);

        var wait = assertThrows(ConflictOperationException.class, () -> service.save(1L, 6L, "device-A", "/work/b"));
        assertThrows(ConflictOperationException.class, () -> service.delete(1L, 6L, "device-A"));
        verify(batches, times(2)).requestStopForBinding(eq(10L), eq(1L), any());
        verify(attempts, times(2)).requestStopForBinding(eq(10L), any());
        verify(bindings, never()).update(isNull(), any());
        var save = ProjectDeviceBindingService.class.getMethod("save", Long.class, Long.class, String.class, String.class);
        var transaction = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()
                .getTransactionAttribute(save, ProjectDeviceBindingService.class);
        assertFalse(transaction.rollbackOn(wait));
        assertTrue(transaction.rollbackOn(new ConflictOperationException("其他写入冲突")));
    }

    @Test
    void blocksDirectoryChangeAndDeletionForAcceptedBatchWithoutTaskAttempt() {
        ProjectDeviceBinding current = binding(1L, "/work/a", null);
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(1L, 6L, "device-A")).thenReturn(current);
        when(batches.countAcceptedByBinding(10L)).thenReturn(1L);
        assertThrows(ConflictOperationException.class, () -> service.save(1L, 6L, "device-A", "/work/b"));
        assertThrows(ConflictOperationException.class, () -> service.delete(1L, 6L, "device-A"));
        verify(attempts, never()).selectCount(any());
        verify(bindings, never()).update(isNull(), any());
    }

    @Test
    void directoryChangeUsesNextRevisionAndRetainsBindingId() {
        ProjectDeviceBinding current = binding(1L, "/work/a", null);
        ProjectDeviceBinding persisted = binding(2L, "/work/b", null);
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(1L, 6L, "device-A")).thenReturn(current);
        when(attempts.selectCount(any())).thenReturn(0L);
        when(bindings.update(isNull(), any())).thenReturn(1);
        when(bindings.selectById(10L)).thenReturn(persisted);

        ProjectDeviceBindingResponse result = service.save(1L, 6L, "device-A", "/work/b");

        assertEquals(10L, result.id());
        assertEquals(2L, result.revision());
        assertEquals("/work/b", result.projectDirectory());
        LambdaUpdateWrapper<ProjectDeviceBinding> update = capturedUpdate();
        assertTrue(update.getSqlSet().contains("project_directory"));
        assertTrue(update.getSqlSet().contains("revision"));
        assertTrue(update.getParamNameValuePairs().containsValue(2L));
        assertTrue(update.getParamNameValuePairs().containsValue("/work/b"));
    }

    @Test
    void deletionAndRestorationPreserveIdentityAndAdvanceRevision() {
        ProjectDeviceBinding current = binding(1L, "/work/a", null);
        ProjectDeviceBinding deleted = binding(2L, "/work/a", LocalDateTime.of(2026, 11, 10, 10, 0));
        ProjectDeviceBinding restored = binding(3L, "/work/b", null);
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(1L, 6L, "device-A")).thenReturn(current, deleted);
        when(attempts.selectCount(any())).thenReturn(0L);
        when(bindings.update(isNull(), any())).thenReturn(1);
        when(bindings.selectById(10L)).thenReturn(restored);

        service.delete(1L, 6L, "device-A");
        ProjectDeviceBindingResponse result = service.save(1L, 6L, "device-A", "/work/b");

        assertEquals(10L, result.id());
        assertEquals(3L, result.revision());
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<ProjectDeviceBinding>> updates = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(bindings, org.mockito.Mockito.times(2)).update(isNull(), updates.capture());
        LambdaUpdateWrapper<ProjectDeviceBinding> delete = updates.getAllValues().get(0);
        LambdaUpdateWrapper<ProjectDeviceBinding> restore = updates.getAllValues().get(1);
        assertTrue(delete.getSqlSet().contains("deleted_at"));
        assertTrue(delete.getParamNameValuePairs().containsValue(2L));
        assertTrue(delete.getParamNameValuePairs().values().stream().anyMatch(LocalDateTime.class::isInstance));
        assertTrue(restore.getSqlSet().contains("deleted_at"));
        assertTrue(restore.getParamNameValuePairs().containsValue(3L));
        assertTrue(restore.getParamNameValuePairs().containsValue(null));
    }

    @Test
    void rejectsNonmemberOtherBindingAndHostDirectoryMismatch() {
        when(projects.lockProject(6L)).thenReturn(6L);
        doThrow(new ForbiddenOperationException("非项目成员")).when(access).requireMember(6L, 99L);
        assertThrows(ForbiddenOperationException.class, () -> service.get(99L, 6L, "device-A"));

        ProjectDeviceBinding current = binding(1L, "/work/a", null);
        when(bindings.selectForUpdate(1L, 6L, "device-A")).thenReturn(current);
        assertThrows(ForbiddenOperationException.class,
                () -> service.requireForBatch(1L, 6L, 11L, "device-A", "/work/a"));
        assertThrows(ForbiddenOperationException.class,
                () -> service.requireForBatch(1L, 6L, 10L, "device-A", "/work/b"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.requireForBatch(1L, 6L, 10L, "device-B", "/work/a"));
        verify(bindings, never()).update(isNull(), any());
    }

    @Test
    void rejectsRelativeOrUnnormalizedPathsBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> service.save(1L, 6L, "device-A", "relative/path"));
        assertThrows(IllegalArgumentException.class, () -> service.save(1L, 6L, "device-A", "/work/../private"));
        assertThrows(IllegalArgumentException.class, () -> service.save(1L, 6L, "device-A", "/work//project"));
        verify(bindings, never()).insert(any(ProjectDeviceBinding.class));
    }

    @Test
    void deletedBindingCannotBeUsedUntilRestored() {
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(1L, 6L, "device-A"))
                .thenReturn(binding(2L, "/work/a", LocalDateTime.of(2026, 11, 10, 10, 0)));
        assertThrows(ResourceNotFoundException.class, () -> service.get(1L, 6L, "device-A"));
        verify(bindings, never()).update(isNull(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<ProjectDeviceBinding> capturedUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<ProjectDeviceBinding>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(bindings).update(isNull(), update.capture());
        return update.getValue();
    }

    private static ProjectDeviceBinding binding(Long revision, String directory, LocalDateTime deletedAt) {
        ProjectDeviceBinding binding = new ProjectDeviceBinding();
        binding.setId(10L);
        binding.setUserId(1L);
        binding.setProjectId(6L);
        binding.setDeviceId("device-A");
        binding.setProjectDirectory(directory);
        binding.setRevision(revision);
        binding.setDeletedAt(deletedAt);
        return binding;
    }
}
