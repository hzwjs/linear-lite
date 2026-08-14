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
import com.linearlite.server.entity.ProjectAgentBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.AgentTaskJobMapper;
import com.linearlite.server.mapper.AgentTaskSessionMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.ProjectAgentBindingMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 任务自动化编排的唯一写入口，保证 assignment/comment 都落到同一 execution_id。 */
@Service
public class AgentTaskOrchestrationService {
    public static final String PI_AGENT_KEY = "pi";
    private static final String ACTIVE = "active";
    private static final String TERMINAL = "done";

    private final UserMapper userMapper;
    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAgentBindingMapper bindingMapper;
    private final AgentTaskSessionMapper sessionMapper;
    private final AgentTaskJobMapper jobMapper;
    private final TaskPermissionGuard taskPermissionGuard;

    public AgentTaskOrchestrationService(
            UserMapper userMapper,
            TaskMapper taskMapper,
            ProjectMapper projectMapper,
            ProjectAgentBindingMapper bindingMapper,
            AgentTaskSessionMapper sessionMapper,
            AgentTaskJobMapper jobMapper,
            TaskPermissionGuard taskPermissionGuard) {
        this.userMapper = userMapper;
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.bindingMapper = bindingMapper;
        this.sessionMapper = sessionMapper;
        this.jobMapper = jobMapper;
        this.taskPermissionGuard = taskPermissionGuard;
    }

    @Transactional(rollbackFor = Exception.class)
    public void onTaskCreated(Task task) {
        if (isPi(task.getAssigneeId())) {
            createAssignment(task);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void onAssigneeChanged(Task before, Task after) {
        boolean wasPi = isPi(before.getAssigneeId());
        boolean isPi = isPi(after.getAssigneeId());
        if (wasPi && !isPi) {
            cancelActiveSession(after.getId());
        }
        if (!wasPi && isPi) {
            createAssignment(after);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void enqueueCommentContinuation(Task task, Long commentId, String body, Long piMentionId) {
        if (!isPi(task.getAssigneeId()) || !isPi(piMentionId) || isTerminal(task.getStatus())) {
            return;
        }
        AgentTaskSession session = activeSession(task.getId());
        if (session == null) {
            return;
        }
        if (jobMapper.selectCount(new LambdaQueryWrapper<AgentTaskJob>()
                .eq(AgentTaskJob::getSourceCommentId, commentId)) > 0) {
            return;
        }
        AgentTaskJob job = newJob(session, task, "comment");
        job.setSourceCommentId(commentId);
        // 评论正文是执行输入，不与失败原因共用字段，避免长评论受错误字段长度限制。
        job.setPrompt(body.trim());
        jobMapper.insert(job);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentJobClaimResponse claim(Long agentUserId) {
        AgentTaskJob job = jobMapper.selectOne(new LambdaQueryWrapper<AgentTaskJob>()
                .in(AgentTaskJob::getStatus, "queued", "leased")
                .le(AgentTaskJob::getNextRunAt, LocalDateTime.now())
                .and(w -> w.isNull(AgentTaskJob::getLeaseUntil)
                        .or().lt(AgentTaskJob::getLeaseUntil, LocalDateTime.now()))
                .orderByAsc(AgentTaskJob::getCreatedAt, AgentTaskJob::getId)
                // selectOne 必须把锁定范围收敛为一条，否则并发队列有多条到期 Job 时会触发多行结果异常。
                .last("LIMIT 1 FOR UPDATE"));
        if (job == null) {
            return null;
        }
        AgentTaskSession session = sessionMapper.selectById(job.getSessionId());
        Task task = taskMapper.selectById(job.getTaskId());
        if (session == null || task == null || !agentUserId.equals(session.getAgentUserId())
                || !ACTIVE.equals(session.getStatus()) || !agentUserId.equals(task.getAssigneeId())) {
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
        // 任务描述就是 Pi 的唯一执行输入；Bridge 自行处理状态回报，不能把编排约定混入用户提示词。
        String prompt = "comment".equals(job.getSourceType())
                ? job.getPrompt()
                : task.getDescription();
        if (prompt == null || prompt.isBlank()) {
            throw new ConflictOperationException("分配给 Pi 的任务必须填写任务描述");
        }
        prompt = prompt.trim();
        // 领取响应只携带项目身份，Bridge 根据 projectId 在本地完成仓库映射；sessionId 不承载路径语义。
        return new AgentJobClaimResponse(job.getId(), session.getExecutionId(), session.getId(),
                project.getId(), project.getName(), task.getTaskKey(), task.getTitle(), task.getDescription(),
                job.getSourceType(), job.getSourceCommentId(), prompt, leaseUntil);
    }

    public List<AgentProjectResponse> listEnabledProjects(Long agentUserId) {
        return bindingMapper.selectEnabledProjects(agentUserId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void heartbeat(Long agentUserId, Long jobId, String executionId) {
        AgentTaskJob job = requireOwnedJob(agentUserId, jobId, executionId);
        job.setStatus("running");
        job.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        jobMapper.updateById(job);
    }

    @Transactional(rollbackFor = Exception.class)
    public void succeed(Long agentUserId, Long jobId, String executionId, String result,
                        TaskCommentWriter commentWriter) {
        AgentTaskJob job = requireOwnedJob(agentUserId, jobId, executionId);
        job.setStatus("succeeded");
        job.setLeaseUntil(null);
        job.setFinishedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        if (result != null && !result.isBlank()) {
            commentWriter.write(job.getTaskKey(), agentUserId, result.trim());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void fail(Long agentUserId, Long jobId, String executionId, String errorMessage,
                     TaskCommentWriter commentWriter) {
        AgentTaskJob job = requireOwnedJob(agentUserId, jobId, executionId);
        job.setStatus("failed");
        job.setLeaseUntil(null);
        job.setErrorMessage(errorMessage == null ? "Pi 执行失败" : errorMessage.trim());
        job.setFinishedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        commentWriter.write(job.getTaskKey(), agentUserId, "Pi 执行失败：" + job.getErrorMessage());
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
        AgentTaskSession session = activeSession(task.getId());
        if (session == null) {
            session = sessionMapper.selectOne(new LambdaQueryWrapper<AgentTaskSession>()
                    .eq(AgentTaskSession::getTaskId, task.getId())
                    .orderByDesc(AgentTaskSession::getCreatedAt)
                    .last("LIMIT 1"));
        }
        if (session == null) return new AgentTaskStatusResponse(null, null, null, null, null, null, null);
        AgentTaskJob job = jobMapper.selectOne(new LambdaQueryWrapper<AgentTaskJob>()
                .eq(AgentTaskJob::getSessionId, session.getId())
                .orderByDesc(AgentTaskJob::getCreatedAt, AgentTaskJob::getId)
                .last("LIMIT 1"));
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

    private void createAssignment(Task task) {
        ProjectAgentBinding binding = bindingMapper.selectOne(new LambdaQueryWrapper<ProjectAgentBinding>()
                .eq(ProjectAgentBinding::getProjectId, task.getProjectId())
                .eq(ProjectAgentBinding::getAgentUserId, task.getAssigneeId())
                .eq(ProjectAgentBinding::getEnabled, true));
        if (binding == null) {
            throw new ConflictOperationException("项目未配置 Pi Agent");
        }
        AgentTaskSession active = activeSession(task.getId());
        if (active != null) {
            return;
        }
        AgentTaskSession session = new AgentTaskSession();
        session.setExecutionId(UUID.randomUUID().toString().replace("-", ""));
        session.setTaskId(task.getId());
        session.setTaskKey(task.getTaskKey());
        session.setProjectId(task.getProjectId());
        session.setAgentUserId(task.getAssigneeId());
        session.setSessionId(session.getExecutionId());
        session.setStatus(ACTIVE);
        sessionMapper.insert(session);
        AgentTaskJob job = newJob(session, task, "assignment");
        jobMapper.insert(job);
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
                .eq(AgentTaskSession::getStatus, ACTIVE)
                .orderByDesc(AgentTaskSession::getCreatedAt)
                .last("LIMIT 1"));
    }

    private void cancelActiveSession(Long taskId) {
        AgentTaskSession session = activeSession(taskId);
        if (session == null) return;
        cancelSession(session);
    }

    private void cancelSession(AgentTaskSession session) {
        session.setStatus("canceled");
        session.setCompletedAt(LocalDateTime.now());
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
        if (!ACTIVE.equals(session.getStatus())
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

    private boolean isPi(Long userId) {
        if (userId == null) return false;
        User user = userMapper.selectById(userId);
        return user != null && Boolean.TRUE.equals(user.getEnabled())
                && "agent".equals(user.getPrincipalType()) && PI_AGENT_KEY.equals(user.getAgentKey());
    }

    private boolean isTerminal(String status) {
        return TERMINAL.equals(status) || "canceled".equals(status) || "duplicate".equals(status);
    }

    @FunctionalInterface
    public interface TaskCommentWriter {
        void write(String taskKey, Long authorId, String body);
    }
}
