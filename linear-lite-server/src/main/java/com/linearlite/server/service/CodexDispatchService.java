package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.TaskLabelResponse;
import com.linearlite.server.dto.codex.CodexDtos;
import com.linearlite.server.entity.*;
import com.linearlite.server.exception.*;
import com.linearlite.server.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/** 任务与 Codex 执行的唯一绑定入口：taskKey -> task snapshot -> run。 */
@Service
public class CodexDispatchService {
    private static final Set<String> NON_TERMINAL = Set.of("queued", "claimed", "running", "needs_input");
    private static final Set<String> EVENT_TYPES = Set.of("status_changed", "command_started", "command_completed", "file_changed", "verification_completed", "input_requested", "run_completed", "run_failed", "desktop_session_started");
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "canceled");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CodexRunnerMapper runnerMapper; private final CodexRunnerEnrollmentCodeMapper enrollmentMapper; private final CodexRepositoryMapper repositoryMapper; private final ProjectCodexBindingMapper bindingMapper; private final CodexRunMapper runMapper; private final CodexRunEventMapper eventMapper; private final CodexRunMessageMapper messageMapper; private final TaskMapper taskMapper; private final TaskCommentMapper taskCommentMapper; private final ProjectMapper projectMapper; private final ProjectMemberMapper projectMemberMapper; private final UserMapper userMapper; private final LabelService labelService; private final ObjectMapper objectMapper; private final TaskStatusService taskStatusService; private final TaskHierarchyCompletionService taskHierarchyCompletionService;
    public CodexDispatchService(CodexRunnerMapper a, CodexRunnerEnrollmentCodeMapper b, CodexRepositoryMapper c, ProjectCodexBindingMapper d, CodexRunMapper e, CodexRunEventMapper f, CodexRunMessageMapper g, TaskMapper h, TaskCommentMapper i, ProjectMapper j, ProjectMemberMapper k, UserMapper l, LabelService m, ObjectMapper n, TaskStatusService o, TaskHierarchyCompletionService p) { runnerMapper=a; enrollmentMapper=b; repositoryMapper=c; bindingMapper=d; runMapper=e; eventMapper=f; messageMapper=g; taskMapper=h; taskCommentMapper=i; projectMapper=j; projectMemberMapper=k; userMapper=l; labelService=m; objectMapper=n; taskStatusService=o; taskHierarchyCompletionService=p; }

    public CodexDtos.EnrollmentCodeResponse createEnrollmentCode(Long userId) {
        requireUser(userId); String code = randomSecret(); CodexRunnerEnrollmentCode row = new CodexRunnerEnrollmentCode(); row.setUserId(userId); row.setCodeHash(hash(code)); row.setExpiresAt(LocalDateTime.now().plusMinutes(10)); enrollmentMapper.insert(row); return new CodexDtos.EnrollmentCodeResponse(code, row.getExpiresAt());
    }
    public List<CodexRunner> listRunners(Long userId) { requireUser(userId); return runnerMapper.selectList(new LambdaQueryWrapper<CodexRunner>().eq(CodexRunner::getUserId,userId).orderByDesc(CodexRunner::getCreatedAt)); }
    public List<CodexRepository> listRepositories(Long runnerId, Long userId) { requireOwnedRunner(runnerId, userId); return repositoryMapper.selectList(new LambdaQueryWrapper<CodexRepository>().eq(CodexRepository::getRunnerId, runnerId).orderByAsc(CodexRepository::getDisplayName)); }
    @Transactional(rollbackFor = Exception.class)
    public void revokeRunner(Long runnerId, Long userId) { CodexRunner r=requireOwnedRunner(runnerId,userId); LocalDateTime now=LocalDateTime.now(); r.setStatus("revoked"); r.setRevokedAt(now); runnerMapper.updateById(r); runMapper.update(null,new LambdaUpdateWrapper<CodexRun>().eq(CodexRun::getRunnerId,runnerId).in(CodexRun::getStatus,NON_TERMINAL).set(CodexRun::getStatus,"failed").set(CodexRun::getErrorCode,"RUNNER_REVOKED").set(CodexRun::getErrorMessage,"Runner 已被撤销").set(CodexRun::getFinishedAt,now)); }
    @Transactional(rollbackFor = Exception.class)
    public CodexDtos.RegisterResponse register(CodexDtos.RegisterRequest request) { if(request==null||blank(request.enrollmentCode())||blank(request.name())) throw new IllegalArgumentException("连接码和 Runner 名称不能为空"); CodexRunnerEnrollmentCode code=enrollmentMapper.selectOne(new LambdaQueryWrapper<CodexRunnerEnrollmentCode>().eq(CodexRunnerEnrollmentCode::getCodeHash,hash(request.enrollmentCode())).isNull(CodexRunnerEnrollmentCode::getConsumedAt).last("FOR UPDATE")); if(code==null||!code.getExpiresAt().isAfter(LocalDateTime.now())) throw new UnauthorizedException("连接码无效或已过期"); code.setConsumedAt(LocalDateTime.now()); enrollmentMapper.updateById(code); String token=randomSecret(); CodexRunner runner=new CodexRunner(); runner.setUserId(code.getUserId()); runner.setName(request.name().trim()); runner.setTokenHash(hash(token)); runner.setStatus("active"); runnerMapper.insert(runner); return new CodexDtos.RegisterResponse(runner.getId(),token); }
    @Transactional(rollbackFor = Exception.class)
    public void heartbeat(Long runnerId, CodexDtos.HeartbeatRequest request) { CodexRunner runner=requireActiveRunner(runnerId); LocalDateTime now=LocalDateTime.now(); runner.setLastSeenAt(now); runnerMapper.updateById(runner); if(request==null||request.repositories()==null) return; for(CodexDtos.RepositoryInput item:request.repositories()){ if(item==null||blank(item.repositoryKey())||blank(item.displayName())||blank(item.remoteIdentity())||blank(item.defaultBranch())) throw new IllegalArgumentException("仓库信息不完整"); if(item.remoteIdentity().contains("@")||item.remoteIdentity().contains("://")) throw new IllegalArgumentException("仓库远程身份不能包含凭据或 URL"); CodexRepository repo=repositoryMapper.selectOne(new LambdaQueryWrapper<CodexRepository>().eq(CodexRepository::getRunnerId,runnerId).eq(CodexRepository::getRepositoryKey,item.repositoryKey())); if(repo==null){repo=new CodexRepository(); repo.setRunnerId(runnerId); repo.setRepositoryKey(item.repositoryKey());} repo.setDisplayName(item.displayName());repo.setRemoteIdentity(item.remoteIdentity());repo.setDefaultBranch(item.defaultBranch());repo.setLastSeenAt(now); if(repo.getId()==null) repositoryMapper.insert(repo); else repositoryMapper.updateById(repo); } }
    public ProjectCodexBinding getBinding(Long projectId,Long userId){ requireProjectOwner(projectId,userId); return bindingMapper.selectOne(new LambdaQueryWrapper<ProjectCodexBinding>().eq(ProjectCodexBinding::getProjectId,projectId)); }
    @Transactional(rollbackFor=Exception.class)
    public ProjectCodexBinding saveBinding(Long projectId,Long userId,CodexDtos.BindingRequest request){ requireProjectOwner(projectId,userId); if(request==null||request.runnerId()==null||request.repositoryId()==null||blank(request.baseBranch())) throw new IllegalArgumentException("Runner、仓库和基础分支不能为空"); CodexRunner runner=requireOwnedRunner(request.runnerId(),userId); if(!"active".equals(runner.getStatus())) throw new IllegalArgumentException("Runner 已撤销"); CodexRepository repo=repositoryMapper.selectById(request.repositoryId()); if(repo==null||!repo.getRunnerId().equals(runner.getId())) throw new IllegalArgumentException("仓库不属于所选 Runner"); ensureCodexProjectMember(projectId); ProjectCodexBinding binding=bindingMapper.selectOne(new LambdaQueryWrapper<ProjectCodexBinding>().eq(ProjectCodexBinding::getProjectId,projectId)); if(binding==null){binding=new ProjectCodexBinding();binding.setProjectId(projectId);binding.setCreatedBy(userId);} binding.setRunnerId(runner.getId());binding.setRepositoryId(repo.getId());binding.setBaseBranch(request.baseBranch().trim()); if(binding.getId()==null) bindingMapper.insert(binding); else bindingMapper.updateById(binding); return binding; }
    @Transactional(rollbackFor=Exception.class)
    public CodexRun dispatch(String taskKey,Long userId,CodexDtos.DispatchRequest request){ if(request==null||blank(request.clientRequestId()))throw new IllegalArgumentException("clientRequestId 不能为空"); CodexRun existing=runMapper.selectOne(new LambdaQueryWrapper<CodexRun>().eq(CodexRun::getCreatedBy,userId).eq(CodexRun::getClientRequestId,request.clientRequestId())); if(existing!=null)return existing; Task task=taskMapper.selectOne(new LambdaQueryWrapper<Task>().eq(Task::getTaskKey,taskKey).last("FOR UPDATE")); if(task==null)throw new ResourceNotFoundException("任务不存在: "+taskKey); requireProjectOwner(task.getProjectId(),userId); return createRun(task,userId,request.clientRequestId(),request.instruction()); }

    /** 负责人切换为 Codex 时由任务命令事务调用；失败会让负责人变更一并回滚。 */
    public CodexRun dispatchAssignedTask(Task task, Long userId) {
        if (task == null || task.getId() == null) throw new IllegalArgumentException("任务不能为空");
        CodexRun active = runMapper.selectOne(new LambdaQueryWrapper<CodexRun>()
                .eq(CodexRun::getTaskId, task.getId())
                .in(CodexRun::getStatus, NON_TERMINAL)
                .orderByDesc(CodexRun::getCreatedAt)
                .last("LIMIT 1"));
        if (active != null) return active;
        return createRun(task, userId, "assignee-" + UUID.randomUUID(), "");
    }

    private CodexRun createRun(Task task,Long userId,String clientRequestId,String instruction){ Long active=runMapper.selectCount(new LambdaQueryWrapper<CodexRun>().eq(CodexRun::getTaskId,task.getId()).in(CodexRun::getStatus,NON_TERMINAL)); if(active!=null&&active>0)throw new ConflictOperationException("CODEX_RUN_ALREADY_ACTIVE"); ProjectCodexBinding binding=bindingMapper.selectOne(new LambdaQueryWrapper<ProjectCodexBinding>().eq(ProjectCodexBinding::getProjectId,task.getProjectId())); if(binding==null)throw new ConflictOperationException("CODEX_BINDING_NOT_CONFIGURED"); CodexRunner runner=requireActiveRunner(binding.getRunnerId()); if(runner.getLastSeenAt()==null||runner.getLastSeenAt().isBefore(LocalDateTime.now().minusSeconds(60)))throw new ConflictOperationException("CODEX_RUNNER_OFFLINE"); CodexRepository repo=repositoryMapper.selectById(binding.getRepositoryId()); if(repo==null||!repo.getRunnerId().equals(runner.getId()))throw new ConflictOperationException("CODEX_BINDING_NOT_CONFIGURED"); labelService.fillLabelsForTasks(List.of(task)); CodexRun run=new CodexRun();String id=UUID.randomUUID().toString();run.setId(id);run.setClientRequestId(clientRequestId);run.setTaskId(task.getId());run.setTaskKey(task.getTaskKey());run.setTaskUpdatedAt(task.getUpdatedAt());run.setTaskSnapshot(snapshot(task));run.setDispatchInstruction(instruction==null?"":instruction.trim());run.setCreatedBy(userId);run.setRunnerId(runner.getId());run.setRepositoryId(repo.getId());run.setBaseBranch(binding.getBaseBranch());run.setBranchName("codex/"+task.getTaskKey().toLowerCase(Locale.ROOT)+"-"+id.substring(0,8));run.setStatus("queued");runMapper.insert(run);return run; }
    public List<CodexRun> listTaskRuns(String taskKey,Long userId){ Task t=requireTaskMember(taskKey,userId); List<CodexRun> runs=runMapper.selectList(new LambdaQueryWrapper<CodexRun>().eq(CodexRun::getTaskId,t.getId()).orderByDesc(CodexRun::getCreatedAt));runs.forEach(this::fillRepositoryKey);return runs; }
    public CodexRun getRun(String id,Long userId){ CodexRun run=requireRun(id); requireTaskMember(run.getTaskKey(),userId); return run; }
    public List<CodexRunEvent> events(String id,Long userId,Long after){ getRun(id,userId);return eventMapper.selectList(new LambdaQueryWrapper<CodexRunEvent>().eq(CodexRunEvent::getRunId,id).gt(after!=null,CodexRunEvent::getSequenceNo,after).orderByAsc(CodexRunEvent::getSequenceNo)); }
    @Transactional(rollbackFor=Exception.class)
    public void sendMessage(String id,Long userId,CodexDtos.MessageRequest request){ CodexRun run=getRun(id,userId);if(!run.getCreatedBy().equals(userId))throw new ForbiddenOperationException("只有执行发起者可以补充指令");if(!"needs_input".equals(run.getStatus()))throw new ConflictOperationException("当前执行不需要补充信息");if(request==null||blank(request.content()))throw new IllegalArgumentException("补充指令不能为空");CodexRunMessage msg=new CodexRunMessage();msg.setRunId(id);msg.setSenderUserId(userId);msg.setContent(request.content().trim());msg.setStatus("pending");messageMapper.insert(msg);}
    @Transactional(rollbackFor=Exception.class)
    public void cancel(String id,Long userId){ CodexRun run=getRun(id,userId);if(!run.getCreatedBy().equals(userId))throw new ForbiddenOperationException("只有执行发起者可以取消");if(TERMINAL.contains(run.getStatus()))return;if("queued".equals(run.getStatus())){run.setStatus("canceled");run.setFinishedAt(LocalDateTime.now());}else run.setCancelRequestedAt(LocalDateTime.now());runMapper.updateById(run);}
    @Transactional(rollbackFor=Exception.class)
    public CodexRun claim(Long runnerId){ requireActiveRunner(runnerId); LocalDateTime now=LocalDateTime.now(); CodexRun run=runMapper.selectOne(new LambdaQueryWrapper<CodexRun>().eq(CodexRun::getRunnerId,runnerId).in(CodexRun::getStatus,Set.of("claimed","running","needs_input")).lt(CodexRun::getLeaseExpiresAt,now).orderByAsc(CodexRun::getCreatedAt).last("LIMIT 1 FOR UPDATE")); if(run==null)run=runMapper.selectOne(new LambdaQueryWrapper<CodexRun>().eq(CodexRun::getRunnerId,runnerId).eq(CodexRun::getStatus,"queued").orderByAsc(CodexRun::getCreatedAt).last("LIMIT 1 FOR UPDATE"));if(run==null)return null;if("queued".equals(run.getStatus())){run.setStatus("claimed");run.setClaimedAt(now);}run.setLeaseExpiresAt(now.plusSeconds(60));runMapper.updateById(run);fillRepositoryKey(run);return run; }
    public void renewLease(String id,Long runnerId){ CodexRun r=requireRunnerRun(id,runnerId);if(!NON_TERMINAL.contains(r.getStatus()))throw new ConflictOperationException("执行已结束");r.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(60));runMapper.updateById(r);}
    public void markDesktopSessionStarted(String id,Long runnerId){ CodexRun r=requireRunnerRun(id,runnerId);if("running".equals(r.getStatus()))return;if(!"claimed".equals(r.getStatus()))throw new ConflictOperationException("当前执行不能启动桌面会话");r.setStatus("running");r.setStartedAt(LocalDateTime.now());runMapper.updateById(r);}
    @Transactional(rollbackFor=Exception.class)
    public void event(String id,Long runnerId,CodexDtos.EventRequest request){ requireRunnerRun(id,runnerId);if(request==null||request.sequenceNo()==null||!EVENT_TYPES.contains(request.eventType())||blank(request.eventPayload()))throw new IllegalArgumentException("执行事件不合法");if(request.eventPayload().length()>8192||request.eventPayload().contains("Authorization")||request.eventPayload().contains("CODEX_API_KEY"))throw new IllegalArgumentException("执行事件包含禁止内容");Long count=eventMapper.selectCount(new LambdaQueryWrapper<CodexRunEvent>().eq(CodexRunEvent::getRunId,id).eq(CodexRunEvent::getSequenceNo,request.sequenceNo()));if(count!=null&&count>0)return;CodexRunEvent e=new CodexRunEvent();e.setRunId(id);e.setSequenceNo(request.sequenceNo());e.setEventType(request.eventType());e.setEventPayload(request.eventPayload());eventMapper.insert(e);}
    @Transactional(rollbackFor=Exception.class)
    public CodexDtos.RunnerMessageResponse claimMessage(String id,Long runnerId){ CodexRun run=requireRunnerRun(id,runnerId);if(!"needs_input".equals(run.getStatus()))return null;CodexRunMessage msg=messageMapper.selectOne(new LambdaQueryWrapper<CodexRunMessage>().eq(CodexRunMessage::getRunId,id).eq(CodexRunMessage::getStatus,"pending").orderByAsc(CodexRunMessage::getCreatedAt).last("FOR UPDATE"));if(msg==null)return null;msg.setStatus("claimed");msg.setClaimedAt(LocalDateTime.now());messageMapper.updateById(msg);return new CodexDtos.RunnerMessageResponse(msg.getId(),msg.getContent());}
    public void consumeMessage(String id,Long runnerId,Long messageId){ CodexRun run=requireRunnerRun(id,runnerId);CodexRunMessage msg=messageMapper.selectById(messageId);if(msg==null||!id.equals(msg.getRunId())||!"claimed".equals(msg.getStatus()))throw new ConflictOperationException("补充消息不能确认消费");msg.setStatus("consumed");msg.setConsumedAt(LocalDateTime.now());messageMapper.updateById(msg);run.setStatus("running");runMapper.updateById(run);}
    @Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
    public void complete(String id,Long runnerId,CodexDtos.CompleteRequest request){
        CodexRun run=runMapper.selectOne(new LambdaQueryWrapper<CodexRun>().eq(CodexRun::getId,id).last("FOR UPDATE"));
        if(run==null)throw new ResourceNotFoundException("Codex 执行不存在: "+id);
        if(!run.getRunnerId().equals(runnerId))throw new ForbiddenOperationException("Runner 无权访问该执行");
        if(TERMINAL.contains(run.getStatus()))return;
        if(request==null||!TERMINAL.contains(request.status()))throw new IllegalArgumentException("终态不合法");
        if("completed".equals(request.status())&&(blank(request.codexThreadId())||blank(request.resultSummary())||blank(request.resultPayload())))throw new IllegalArgumentException("完成执行必须提供 Codex 会话与结构化结果");
        if("failed".equals(request.status())&&blank(request.errorCode()))throw new IllegalArgumentException("失败执行必须提供错误码");
        if("completed".equals(request.status())){
            LocalDateTime completedAt=LocalDateTime.now();
            User codexUser = requireCodexUser();
            taskStatusService.updateState(run.getTaskId(), "done", 100, codexUser.getId(), completedAt);
            taskHierarchyCompletionService.completeEligibleAncestors(run.getTaskId(), codexUser.getId(), completedAt);
            TaskComment comment=new TaskComment();
            comment.setTaskId(run.getTaskId());
            comment.setAuthorId(codexUser.getId());
            comment.setBody("**Codex 执行结果**\n\n"+request.resultSummary().trim());
            comment.setDepth(0);
            comment.setCreatedAt(completedAt);
            taskCommentMapper.insert(comment);
        }
        run.setStatus(request.status());run.setCodexThreadId(request.codexThreadId());run.setResultSummary(request.resultSummary());run.setResultPayload(request.resultPayload());run.setErrorCode(request.errorCode());run.setErrorMessage(request.errorMessage());run.setFinishedAt(LocalDateTime.now());runMapper.updateById(run);
    }
    public void needsInput(String id,Long runnerId,String question){ CodexRun run=requireRunnerRun(id,runnerId);if(!"running".equals(run.getStatus())||blank(question))throw new IllegalArgumentException("不能进入等待补充状态");run.setStatus("needs_input");run.setResultSummary(question);runMapper.updateById(run);}
    public CodexRunner authenticate(String token){if(blank(token))throw new UnauthorizedException("缺少 Runner Token");CodexRunner r=runnerMapper.selectOne(new LambdaQueryWrapper<CodexRunner>().eq(CodexRunner::getTokenHash,hash(token)));if(r==null||!"active".equals(r.getStatus()))throw new UnauthorizedException("Runner Token 无效");return r;}
    private Task requireTaskMember(String key,Long user){Task t=taskMapper.selectOne(new LambdaQueryWrapper<Task>().eq(Task::getTaskKey,key));if(t==null)throw new ResourceNotFoundException("任务不存在: "+key);requireProjectMember(t.getProjectId(),user);return t;}
    private CodexRun requireRun(String id){CodexRun r=runMapper.selectById(id);if(r==null)throw new ResourceNotFoundException("Codex 执行不存在: "+id);fillRepositoryKey(r);return r;}
    private void fillRepositoryKey(CodexRun run){CodexRepository repo=repositoryMapper.selectById(run.getRepositoryId());if(repo==null)throw new ResourceNotFoundException("执行仓库不存在");run.setRepositoryKey(repo.getRepositoryKey());}
    private CodexRun requireRunnerRun(String id,Long runnerId){CodexRun r=requireRun(id);if(!r.getRunnerId().equals(runnerId))throw new ForbiddenOperationException("Runner 无权访问该执行");return r;}
    private CodexRunner requireOwnedRunner(Long id,Long user){CodexRunner r=runnerMapper.selectById(id);if(r==null)throw new ResourceNotFoundException("Runner 不存在");if(!r.getUserId().equals(user))throw new ForbiddenOperationException("Runner 不属于当前用户");return r;}
    private CodexRunner requireActiveRunner(Long id){CodexRunner r=runnerMapper.selectById(id);if(r==null||!"active".equals(r.getStatus()))throw new UnauthorizedException("Runner 已撤销或不存在");return r;}
    private void requireProjectOwner(Long projectId,Long user){Project p=projectMapper.selectById(projectId);if(p==null)throw new ResourceNotFoundException("项目不存在");if(!p.getCreatorId().equals(user))throw new ForbiddenOperationException("只有项目创建者可以配置或派发 Codex");}
    private void requireProjectMember(Long projectId,Long user){if(user==null)throw new UnauthorizedException("当前用户未登录"); Long count=projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId,projectId).eq(ProjectMember::getUserId,user));if(count==null||count==0)throw new ForbiddenOperationException("你不是该项目成员");}
    private void ensureCodexProjectMember(Long projectId){User codex=requireCodexUser();Long count=projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId,projectId).eq(ProjectMember::getUserId,codex.getId()));if(count!=null&&count>0)return;ProjectMember member=new ProjectMember();member.setProjectId(projectId);member.setUserId(codex.getId());member.setRole("member");member.setSortOrder(0);member.setCreatedAt(LocalDateTime.now());projectMemberMapper.insert(member);}
    private User requireCodexUser(){List<User> users=userMapper.selectList(new LambdaQueryWrapper<User>().eq(User::getUserType,User.TYPE_CODEX));if(users.size()!=1)throw new IllegalStateException("Codex 系统负责人必须且只能存在一个");return users.get(0);}
    private String snapshot(Task t){Map<String,Object> m=new LinkedHashMap<>();Project p=projectMapper.selectById(t.getProjectId());m.put("taskKey",t.getTaskKey());m.put("taskId",t.getId());m.put("projectIdentifier",p.getIdentifier());m.put("projectName",p.getName());m.put("title",t.getTitle());m.put("description",t.getDescription());m.put("status",t.getStatus());m.put("priority",t.getPriority());m.put("labels",t.getLabels()==null?List.of():t.getLabels().stream().map(TaskLabelResponse::getName).toList());m.put("dueDate",t.getDueDate());m.put("plannedStartDate",t.getPlannedStartDate());m.put("updatedAt",t.getUpdatedAt());try{return objectMapper.writeValueAsString(m);}catch(JsonProcessingException e){throw new IllegalStateException("任务快照序列化失败",e);}}
    private static void requireUser(Long id){if(id==null)throw new UnauthorizedException("当前用户未登录");} private static boolean blank(String s){return s==null||s.isBlank();} private static String randomSecret(){byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);} private static String hash(String value){try{return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
