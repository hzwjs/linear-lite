package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linearlite.server.dto.ProjectDeviceBindingResponse;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 用户、项目与设备的目录绑定。用户标识由调用方的认证上下文提供。 */
@Service
public class ProjectDeviceBindingService {
    private final ProjectDeviceBindingMapper bindingMapper;
    private final TaskExecutionAttemptMapper attemptMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessGuard accessGuard;
    private final CollaborationBatchMapper batchMapper;

    public ProjectDeviceBindingService(ProjectDeviceBindingMapper bindingMapper,
                                       TaskExecutionAttemptMapper attemptMapper,
                                       ProjectMapper projectMapper,
                                       ProjectAccessGuard accessGuard,
                                       CollaborationBatchMapper batchMapper) {
        this.bindingMapper = bindingMapper;
        this.attemptMapper = attemptMapper;
        this.projectMapper = projectMapper;
        this.accessGuard = accessGuard;
        this.batchMapper = batchMapper;
    }

    @Transactional(rollbackFor = Exception.class, noRollbackFor = BindingStoppingException.class,
            isolation = Isolation.READ_COMMITTED)
    public ProjectDeviceBindingResponse save(Long authenticatedUserId, Long projectId,
                                             String deviceId, String projectDirectory) {
        requireDevice(deviceId);
        requireHostNormalizedAbsoluteDirectory(projectDirectory);
        lockProject(authenticatedUserId, projectId);
        ProjectDeviceBinding current = bindingMapper.selectForUpdate(authenticatedUserId, projectId, deviceId);
        if (current == null) {
            ProjectDeviceBinding created = new ProjectDeviceBinding();
            created.setUserId(authenticatedUserId);
            created.setProjectId(projectId);
            created.setDeviceId(deviceId);
            created.setProjectDirectory(projectDirectory);
            created.setRevision(1L);
            bindingMapper.insert(created);
            return ProjectDeviceBindingResponse.from(requirePersisted(created.getId()));
        }
        if (current.getDeletedAt() == null && Objects.equals(current.getProjectDirectory(), projectDirectory)) {
            return ProjectDeviceBindingResponse.from(current);
        }
        stopBindingBeforeChange(current);
        long nextRevision = Math.addExact(current.getRevision(), 1L);
        int updated = bindingMapper.update(null, new LambdaUpdateWrapper<ProjectDeviceBinding>()
                .eq(ProjectDeviceBinding::getId, current.getId())
                .set(ProjectDeviceBinding::getProjectDirectory, projectDirectory)
                .set(ProjectDeviceBinding::getDeletedAt, null)
                .set(ProjectDeviceBinding::getRevision, nextRevision));
        if (updated != 1) throw new ConflictOperationException("项目绑定发生变化");
        return ProjectDeviceBindingResponse.from(requirePersisted(current.getId()));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ProjectDeviceBindingResponse get(Long authenticatedUserId, Long projectId, String deviceId) {
        requireDevice(deviceId);
        lockProject(authenticatedUserId, projectId);
        return ProjectDeviceBindingResponse.from(requireActive(
                bindingMapper.selectForUpdate(authenticatedUserId, projectId, deviceId)));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public List<ProjectDeviceBindingResponse> list(Long authenticatedUserId, Long projectId) {
        lockProject(authenticatedUserId, projectId);
        return bindingMapper.selectList(new LambdaQueryWrapper<ProjectDeviceBinding>()
                .eq(ProjectDeviceBinding::getUserId, authenticatedUserId)
                .eq(ProjectDeviceBinding::getProjectId, projectId)
                .isNull(ProjectDeviceBinding::getDeletedAt)
                .orderByAsc(ProjectDeviceBinding::getId)).stream().map(ProjectDeviceBindingResponse::from).toList();
    }

    @Transactional(rollbackFor = Exception.class, noRollbackFor = BindingStoppingException.class,
            isolation = Isolation.READ_COMMITTED)
    public void delete(Long authenticatedUserId, Long projectId, String deviceId) {
        requireDevice(deviceId);
        lockProject(authenticatedUserId, projectId);
        ProjectDeviceBinding current = requireActive(
                bindingMapper.selectForUpdate(authenticatedUserId, projectId, deviceId));
        stopBindingBeforeChange(current);
        int updated = bindingMapper.update(null, new LambdaUpdateWrapper<ProjectDeviceBinding>()
                .eq(ProjectDeviceBinding::getId, current.getId())
                .set(ProjectDeviceBinding::getDeletedAt, LocalDateTime.now())
                .set(ProjectDeviceBinding::getRevision, Math.addExact(current.getRevision(), 1L)));
        if (updated != 1) throw new ConflictOperationException("项目绑定发生变化");
    }

    /** 调用方事务贯穿绑定读取、项目锁及批次签发或验证。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public ProjectDeviceBinding requireForBatch(Long authenticatedUserId, Long projectId, Long bindingId,
                                                String deviceId, String projectDirectory) {
        requireDevice(deviceId);
        requireHostNormalizedAbsoluteDirectory(projectDirectory);
        lockProject(authenticatedUserId, projectId);
        ProjectDeviceBinding binding = requireActive(
                bindingMapper.selectForUpdate(authenticatedUserId, projectId, deviceId));
        if (!Objects.equals(binding.getId(), bindingId)
                || !Objects.equals(binding.getProjectDirectory(), projectDirectory)) {
            throw new ForbiddenOperationException("项目绑定与本机目录不匹配");
        }
        return binding;
    }

    private void lockProject(Long authenticatedUserId, Long projectId) {
        if (projectId == null || projectId <= 0) throw new IllegalArgumentException("项目标识无效");
        if (authenticatedUserId == null) throw new IllegalArgumentException("当前用户未登录");
        if (projectMapper.lockProject(projectId) == null) {
            throw new ResourceNotFoundException("项目不存在");
        }
        accessGuard.requireMember(projectId, authenticatedUserId);
    }

    private ProjectDeviceBinding requirePersisted(Long bindingId) {
        return requireActive(bindingMapper.selectById(bindingId));
    }

    private ProjectDeviceBinding requireActive(ProjectDeviceBinding binding) {
        if (binding == null || binding.getDeletedAt() != null) {
            throw new ResourceNotFoundException("本机项目绑定不存在");
        }
        return binding;
    }

    private void stopBindingBeforeChange(ProjectDeviceBinding binding) {
        Long bindingId = binding.getId();
        batchMapper.requestStopForBinding(bindingId, binding.getRevision(), LocalDateTime.now(ZoneOffset.UTC));
        attemptMapper.requestStopForBinding(bindingId, com.linearlite.server.time.BeijingTime.now());
        if (batchMapper.countAcceptedByBinding(bindingId) > 0)
            throw new BindingStoppingException("协作批次等待停止确认，项目绑定保持冻结");
        Long count = attemptMapper.selectCount(new LambdaQueryWrapper<TaskExecutionAttempt>()
                .eq(TaskExecutionAttempt::getBindingId, bindingId)
                .isNull(TaskExecutionAttempt::getStopConfirmedAt));
        if (count > 0)
            throw new BindingStoppingException("执行记录等待停止确认，项目绑定保持冻结");
    }

    /** 提交停止意图后返回冲突，目录与版本在停止确认前保持原值。 */
    private static final class BindingStoppingException extends ConflictOperationException {
        BindingStoppingException(String message) { super(message); }
    }

    private void requireDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || !deviceId.equals(deviceId.trim())
                || deviceId.length() > 128) {
            throw new IllegalArgumentException("设备标识无效");
        }
    }

    /** 实际目录、符号链接与设备由执行宿主核实；服务端校验路径声明的语法。 */
    private void requireHostNormalizedAbsoluteDirectory(String directory) {
        if (directory == null || directory.isBlank() || !directory.equals(directory.trim())
                || directory.length() > 2048 || directory.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("项目目录声明无效");
        }
        boolean unix = directory.startsWith("/") && !directory.startsWith("//");
        boolean windowsDrive = directory.matches("^[A-Za-z]:[\\\\/].*");
        boolean unc = directory.startsWith("\\\\");
        if (!unix && !windowsDrive && !unc) throw new IllegalArgumentException("项目目录须为绝对路径");
        String parts = unix ? directory : directory.replace('\\', '/');
        if (unc) parts = parts.substring(2);
        if (parts.contains("//") || Arrays.stream(parts.split("/", -1))
                .anyMatch(part -> part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("项目目录声明缺少规范化");
        }
        boolean root = parts.equals("/") || parts.matches("^[A-Za-z]:/$");
        if (!root && parts.endsWith("/")) throw new IllegalArgumentException("项目目录声明缺少规范化");
        if (unc && parts.split("/").length < 2) throw new IllegalArgumentException("项目目录声明无效");
    }
}
