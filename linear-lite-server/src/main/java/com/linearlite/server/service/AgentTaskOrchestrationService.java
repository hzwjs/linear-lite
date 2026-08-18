package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.linearlite.server.dto.AgentJobClaimResponse;
import com.linearlite.server.dto.AgentProjectResponse;
import com.linearlite.server.dto.AgentSessionResponse;
import com.linearlite.server.dto.AgentSessionStateRequest;
import com.linearlite.server.dto.AgentTaskStatusResponse;
import com.linearlite.server.dto.CreateTaskCommentRequest;
import com.linearlite.server.entity.AgentTaskJob;
import com.linearlite.server.entity.AgentTaskSession;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.AgentTaskJobMapper;
import com.linearlite.server.mapper.AgentTaskSessionMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 任务自动化编排的唯一写入口，保证 assignment/comment 都落到同一 execution_id。 */
@Service
public class AgentTaskOrchestrationService {
    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final AgentTaskSessionMapper sessionMapper;
    private final AgentTaskJobMapper jobMapper;
    private final TaskPermissionGuard taskPermissionGuard;

    public AgentTaskOrchestrationService(
            TaskMapper taskMapper,
            ProjectMapper projectMapper,
            ProjectMemberMapper projectMemberMapper,
            AgentTaskSessionMapper sessionMapper,
            AgentTaskJobMapper jobMapper,
            TaskPermissionGuard taskPermissionGuard) {
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.sessionMapper = sessionMapper;
        this.jobMapper = jobMapper;
        this.taskPermissionGuard = taskPermissionGuard;
    }

    /** 负责人显式准备上下文；此操作不创建 Job，也不启动 Pi。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskStatusResponse prepare(Long ownerUserId, String taskKey) {
        Task task = taskPermissionGuard.requireTaskAccessByKey(taskKey, ownerUserId);
        requireOwner(task, ownerUserId);
        // 任务流程状态与 Pi 会话生命周期相互独立；已完成任务仍需恢复原会话继续处理反馈。
        AgentTaskSession session = activeSession(task.getId());
        if (session == null) {
            session = new AgentTaskSession();
            session.setExecutionId(UUID.randomUUID().toString().replace("-", ""));
            session.setTaskId(task.getId());
            session.setTaskKey(task.getTaskKey());
            session.setProjectId(task.getProjectId());
            session.setAgentUserId(ownerUserId);
            session.setSessionId(session.getExecutionId());
            session.setStatus("waiting_input");
            sessionMapper.insert(session);
        }
        return taskStatus(session, currentTurnJob(session.getId()));
    }

    /** 负责人提交一轮补充内容后才创建 queued Job；普通评论不会走此入口。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskStatusResponse submitTurn(Long ownerUserId, String taskKey, String executionId, String prompt) {
        Task task = taskPermissionGuard.requireTaskAccessByKey(taskKey, ownerUserId);
        requireOwner(task, ownerUserId);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("本轮补充内容不能为空");
        }
        AgentTaskSession session = requireActiveSession(task.getId(), executionId, ownerUserId);
        if (!"waiting_input".equals(session.getStatus())) {
            throw new ConflictOperationException("当前本地 Pi 正在执行，请等待本轮完成");
        }
        if (jobMapper.selectCount(new LambdaQueryWrapper<AgentTaskJob>()
                .eq(AgentTaskJob::getSessionId, session.getId())
                .eq(AgentTaskJob::getSourceType, "turn")
                .in(AgentTaskJob::getStatus, "queued", "leased", "running")) > 0) {
            throw new ConflictOperationException("当前执行上下文已有未完成轮次");
        }
        AgentTaskJob job = newJob(session, task, "turn");
        job.setPrompt(prompt.trim());
        jobMapper.insert(job);
        return taskStatus(session, job);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentJobClaimResponse claim(Long ownerUserId) {
        // 领取查询在数据库层绑定负责人，避免某个 Bridge 先拿到其他负责人的 Job 再取消它。
        // Job 时间由 Java 统一写入，领取比较也使用同一时钟，避免数据库 UTC 与应用本地时区造成 8 小时漂移。
        AgentTaskJob job = jobMapper.selectClaimableForOwner(ownerUserId, LocalDateTime.now());
        if (job == null) {
            return null;
        }
        AgentTaskSession session = sessionMapper.selectById(job.getSessionId());
        Task task = taskMapper.selectById(job.getTaskId());
        if (session == null || task == null || !ownerUserId.equals(session.getAgentUserId())
                || (!"waiting_input".equals(session.getStatus()) && !"running".equals(session.getStatus()))
                || !ownerUserId.equals(task.getAssigneeId())) {
            job.setStatus("canceled");
            job.setFinishedAt(LocalDateTime.now());
            job.setErrorMessage("任务负责人或会话已变化");
            jobMapper.updateById(job);
            return null;
        }
        Project project = projectMapper.selectById(task.getProjectId());
        if (project == null) {
            throw new ResourceNotFoundException("任务所属项目不存在");
        }
        LocalDateTime leaseUntil = LocalDateTime.now().plusMinutes(5);
        job.setStatus("leased");
        job.setAttemptCount(job.getAttemptCount() == null ? 1 : job.getAttemptCount() + 1);
        job.setLeaseUntil(leaseUntil);
        job.setStartedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        // 负责人提交的 Turn prompt 是本轮唯一执行输入；准备上下文不会隐式执行任务描述。
        String prompt = job.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            throw new ConflictOperationException("分配给 Pi 的任务必须填写任务描述");
        }
        prompt = prompt.trim();
        // 领取响应只携带项目身份，Bridge 根据 projectId 在本地完成仓库映射；sessionId 不承载路径语义。
        session.setStatus("running");
        sessionMapper.updateById(session);
        return new AgentJobClaimResponse(job.getId(), session.getExecutionId(), session.getId(),
                project.getId(), project.getName(), task.getTaskKey(), task.getTitle(), task.getDescription(),
                job.getSourceType(), job.getSourceCommentId(), prompt, leaseUntil);
    }

    public List<AgentProjectResponse> listEnabledProjects(Long agentUserId) {
        List<Long> projectIds = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getUserId, agentUserId)
                .orderByAsc(ProjectMember::getProjectId))
                .stream().map(ProjectMember::getProjectId).distinct().toList();
        return projectMapper.selectBatchIds(projectIds).stream()
                .sorted(java.util.Comparator.comparing(Project::getName).thenComparing(Project::getId))
                .map(project -> new AgentProjectResponse(project.getId(), project.getName()))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void heartbeat(Long ownerUserId, Long jobId, String executionId) {
        AgentTaskJob job = requireOwnedJob(ownerUserId, jobId, executionId);
        job.setStatus("running");
        job.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        jobMapper.updateById(job);
    }

    @Transactional(rollbackFor = Exception.class)
    public void succeed(Long ownerUserId, Long jobId, String executionId, String result,
                        TaskCommentWriter commentWriter) {
        AgentTaskJob job = requireOwnedJob(ownerUserId, jobId, executionId);
        job.setStatus("succeeded");
        job.setLeaseUntil(null);
        job.setFinishedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        if (result != null && !result.isBlank()) {
            commentWriter.write(job.getTaskKey(), ownerUserId, result.trim());
        }
        AgentTaskSession session = sessionMapper.selectById(job.getSessionId());
        session.setStatus("waiting_input");
        sessionMapper.updateById(session);
    }

    @Transactional(rollbackFor = Exception.class)
    public void fail(Long ownerUserId, Long jobId, String executionId, String errorMessage,
                     TaskCommentWriter commentWriter) {
        AgentTaskJob job = requireOwnedJob(ownerUserId, jobId, executionId);
        job.setStatus("failed");
        job.setLeaseUntil(null);
        job.setErrorMessage(errorMessage == null ? "Pi 执行失败" : errorMessage.trim());
        job.setFinishedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        commentWriter.write(job.getTaskKey(), ownerUserId, "本地 Pi 执行失败：" + job.getErrorMessage());
        AgentTaskSession session = sessionMapper.selectById(job.getSessionId());
        session.setStatus("waiting_input");
        sessionMapper.updateById(session);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long agentUserId, String executionId) {
        AgentTaskSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AgentTaskSession>()
                .eq(AgentTaskSession::getExecutionId, executionId)
                .eq(AgentTaskSession::getAgentUserId, agentUserId));
        if (session == null) {
            throw new ResourceNotFoundException("执行会话不存在");
        }
        cancelSession(session);
    }

    /** 人类用户先通过任务权限校验，再复用同一会话取消逻辑。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancelTask(Long userId, String taskKey, String executionId) {
        Task task = taskPermissionGuard.requireTaskAccessByKey(taskKey, userId);
        requireOwner(task, userId);
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        AgentTaskSession session = activeSession(task.getId());
        if (session == null) {
            throw new ConflictOperationException("当前任务没有可取消的执行");
        }
        if (!executionId.equals(session.getExecutionId())) {
            throw new ConflictOperationException("执行会话已变化，请刷新后重试");
        }
        cancelSession(session);
    }

    public AgentSessionResponse getSession(Long agentUserId, String executionId) {
        AgentTaskSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AgentTaskSession>()
                .eq(AgentTaskSession::getExecutionId, executionId)
                .eq(AgentTaskSession::getAgentUserId, agentUserId));
        if (session == null) {
            throw new ResourceNotFoundException("执行会话不存在");
        }
        return new AgentSessionResponse(session.getExecutionId(), session.getTaskKey(), session.getStatus(),
                session.getSessionId(),
                session.getCreatedAt(), session.getUpdatedAt(), session.getCompletedAt());
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateSessionState(Long agentUserId, String executionId, AgentSessionStateRequest request) {
        AgentTaskSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AgentTaskSession>()
                .eq(AgentTaskSession::getExecutionId, executionId)
                .eq(AgentTaskSession::getAgentUserId, agentUserId));
        if (session == null) throw new ResourceNotFoundException("执行会话不存在");
        // sessionId 只作为不透明的会话身份保存；本地 session 文件路径始终由 Bridge 自己管理。
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new IllegalArgumentException("Pi sessionId 不能为空");
        }
        session.setSessionId(request.getSessionId());
        sessionMapper.updateById(session);
    }

    public AgentTaskStatusResponse getTaskStatus(String taskKey, Long userId) {
        Task task = taskPermissionGuard.requireTaskAccessByKey(taskKey, userId);
        requireOwner(task, userId);
        AgentTaskSession session = activeSession(task.getId());
        if (session == null) {
            session = sessionMapper.selectOne(new LambdaQueryWrapper<AgentTaskSession>()
                    .eq(AgentTaskSession::getTaskId, task.getId())
                    .orderByDesc(AgentTaskSession::getCreatedAt)
                    .last("LIMIT 1"));
        }
        if (session == null) return new AgentTaskStatusResponse(null, null, null, null, null, null, null);
        AgentTaskJob job = currentTurnJob(session.getId());
        return new AgentTaskStatusResponse(session.getExecutionId(), job == null ? null : job.getId(), session.getStatus(),
                job == null ? null : job.getStatus(), job == null ? null : job.getSourceType(),
                job == null ? null : job.getErrorMessage(), session.getUpdatedAt());
    }

    /** Bridge 专用状态查询允许读取 canceled 终态，但仍必须校验 Agent、Job、会话和任务归属。 */
    public AgentTaskStatusResponse getAgentJobStatus(Long agentUserId, Long jobId, String executionId) {
        AgentTaskJob job = requireAgentJob(agentUserId, jobId, executionId);
        AgentTaskSession session = sessionMapper.selectById(job.getSessionId());
        return new AgentTaskStatusResponse(session.getExecutionId(), job.getId(), session.getStatus(), job.getStatus(),
                job.getSourceType(), job.getErrorMessage(), session.getUpdatedAt());
    }

    private AgentTaskJob newJob(AgentTaskSession session, Task task, String sourceType) {
        AgentTaskJob job = new AgentTaskJob();
        job.setSessionId(session.getId());
        job.setExecutionId(session.getExecutionId());
        job.setTaskId(task.getId());
        job.setTaskKey(task.getTaskKey());
        job.setSourceType(sourceType);
        job.setStatus("queued");
        job.setAttemptCount(0);
        job.setNextRunAt(LocalDateTime.now());
        return job;
    }

    private AgentTaskSession activeSession(Long taskId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<AgentTaskSession>()
                .eq(AgentTaskSession::getTaskId, taskId)
                .in(AgentTaskSession::getStatus, "waiting_input", "running")
                .orderByDesc(AgentTaskSession::getCreatedAt)
                .last("LIMIT 1"));
    }

    private void requireOwner(Task task, Long ownerUserId) {
        if (ownerUserId == null || !ownerUserId.equals(task.getAssigneeId())) {
            throw new ConflictOperationException("只有任务负责人可以安排本地 Pi");
        }
    }

    private AgentTaskSession requireActiveSession(Long taskId, String executionId, Long ownerUserId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        AgentTaskSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AgentTaskSession>()
                .eq(AgentTaskSession::getTaskId, taskId)
                .eq(AgentTaskSession::getExecutionId, executionId)
                .eq(AgentTaskSession::getAgentUserId, ownerUserId)
                .in(AgentTaskSession::getStatus, "waiting_input", "running")
                .last("LIMIT 1"));
        if (session == null) {
            throw new ConflictOperationException("执行上下文不存在或已结束，请重新准备本地 Pi");
        }
        return session;
    }

    private AgentTaskJob currentTurnJob(Long sessionId) {
        return jobMapper.selectOne(new LambdaQueryWrapper<AgentTaskJob>()
                .eq(AgentTaskJob::getSessionId, sessionId)
                .eq(AgentTaskJob::getSourceType, "turn")
                .in(AgentTaskJob::getStatus, "queued", "leased", "running")
                .orderByDesc(AgentTaskJob::getCreatedAt, AgentTaskJob::getId)
                .last("LIMIT 1"));
    }

    private AgentTaskStatusResponse taskStatus(AgentTaskSession session, AgentTaskJob job) {
        return new AgentTaskStatusResponse(session.getExecutionId(), job == null ? null : job.getId(),
                session.getStatus(), job == null ? null : job.getStatus(),
                job == null ? null : job.getSourceType(), job == null ? null : job.getErrorMessage(),
                session.getUpdatedAt());
    }

    private void cancelSession(AgentTaskSession session) {
        // 终止的是当前 Job，不是整个任务执行上下文；负责人可以在同一 Context 中重新提交下一轮。
        session.setStatus("waiting_input");
        session.setCompletedAt(null);
        sessionMapper.updateById(session);
        jobMapper.update(null, new UpdateWrapper<AgentTaskJob>()
                .eq("session_id", session.getId())
                .in("status", "queued", "leased", "running")
                .set("status", "canceled")
                .set("finished_at", LocalDateTime.now()));
    }

    /** Agent 事件与结果回报必须复用同一 Job 所有权校验。 */
    AgentTaskJob requireOwnedJob(Long agentUserId, Long jobId, String executionId) {
        AgentTaskJob job = requireAgentJob(agentUserId, jobId, executionId);
        AgentTaskSession session = sessionMapper.selectById(job.getSessionId());
        if (!("waiting_input".equals(session.getStatus()) || "running".equals(session.getStatus()))
                || !("leased".equals(job.getStatus()) || "running".equals(job.getStatus()))) {
            throw new ConflictOperationException("Agent Job 已过期或不属于当前执行会话");
        }
        return job;
    }

    private AgentTaskJob requireAgentJob(Long agentUserId, Long jobId, String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        AgentTaskJob job = jobMapper.selectById(jobId);
        AgentTaskSession session = job == null ? null : sessionMapper.selectById(job.getSessionId());
        Task task = job == null ? null : taskMapper.selectById(job.getTaskId());
        if (job == null || session == null || !agentUserId.equals(session.getAgentUserId())
                || !executionId.equals(job.getExecutionId())
                || task == null || !agentUserId.equals(task.getAssigneeId())
                || !executionId.equals(session.getExecutionId())) {
            throw new ConflictOperationException("Agent Job 已过期或不属于当前执行会话");
        }
        return job;
    }

    /** 首个进度事件即表示 Bridge 已开始执行，立即把租约状态推进为 running。 */
    void markJobRunning(AgentTaskJob job) {
        job.setStatus("running");
        job.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        jobMapper.updateById(job);
    }

    @FunctionalInterface
    public interface TaskCommentWriter {
        void write(String taskKey, Long authorId, String body);
    }
}
