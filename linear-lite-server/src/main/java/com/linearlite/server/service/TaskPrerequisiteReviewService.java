package com.linearlite.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.ArtifactObservation;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.PrerequisiteAdoption;
import com.linearlite.server.dto.PrerequisiteReviewRequest;
import com.linearlite.server.dto.PrerequisiteReviewStatus;
import com.linearlite.server.dto.PrerequisiteSourceObservation;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 前置交付由下游本次尝试复核；事务与任务领取共用项目协调锁。 */
@Service
public class TaskPrerequisiteReviewService {
    private static final String KIND = "PREREQUISITE_REVIEW";
    private static final ObjectMapper FACT_JSON = new ObjectMapper();
    private final TaskExecutionAccessService access;
    private final TaskCollaborationInputService inputs;
    private final TaskExecutionOperationService operations;
    private final TaskExecutionAttemptMapper attempts;
    private final ObjectMapper json;

    public TaskPrerequisiteReviewService(TaskExecutionAccessService access, TaskCollaborationInputService inputs,
                                        TaskExecutionOperationService operations, TaskExecutionAttemptMapper attempts,
                                        ObjectMapper json) {
        this.access = access; this.inputs = inputs; this.operations = operations;
        this.attempts = attempts; this.json = json;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public JsonNode review(Long userId, CollaborationBatchRequest batch, Long taskId,
                           CollaborationAttemptCommand command, PrerequisiteReviewRequest review) {
        validateReview(review);
        var owned = access.requireOwnedAttempt(userId, batch, taskId, command);
        ObjectNode request = json.createObjectNode().put("taskId", taskId)
                .put("batchId", owned.context().batchId()).put("bindingId", owned.context().bindingId())
                .put("attemptId", command.attemptId()).put("inputRevision", command.inputRevision())
                .put("hostInstanceId", command.hostInstanceId()).put("agentInstanceId", command.agentInstanceId());
        request.set("review", json.valueToTree(review));
        CollaborationCredentialPolicy.requireSafe(request, batch.batchToken(), command.claimToken());
        CollaborationCredentialPolicy.requireSafe(command.operationId(), batch.batchToken(), command.claimToken());
        JsonNode replay = operations.findReplay(owned.context().projectId(), userId, command.operationId(), "prerequisite_review", request);
        if (replay != null) return replay;
        var check = access.checkWritable(owned, command);
        ObjectNode response = json.createObjectNode().put("accepted", check.accepted());
        if (!check.accepted()) {
            response.put("reason", check.reason());
        } else {
            JsonNode snapshot = owned.attempt().getPrerequisiteVersions();
            JsonNode source = null;
            if (snapshot != null && snapshot.isArray()) for (JsonNode item : snapshot) {
                if (item.path("taskId").asLong() == review.sourceTaskId()
                        && item.path("attemptId").asLong() == review.sourceAttemptId()
                        && item.path("inputRevision").asLong() == review.sourceInputRevision()) source = item;
            }
            if (source == null) throw new ConflictOperationException("前置成果已变化，请重新观察");
            JsonNode expected = source.path("evidence").path("artifactObservations");
            if ("ADOPT".equals(review.decision()) && expected.isArray() && !expected.isEmpty()) {
                if (review.observations().isEmpty()) throw new ConflictOperationException("采用的文件缺少读取事实");
                for (ArtifactObservation observation : review.observations()) {
                    JsonNode actual = json.valueToTree(observation);
                    boolean matches = false;
                    for (JsonNode candidate : expected) if (candidate.equals(actual)) matches = true;
                    if (!matches) matches = previouslyAdoptedAndEdited(owned.attempt(), review, observation);
                    if (!matches) throw new ConflictOperationException("采用的文件与领取时的痕迹不一致");
                }
            }
            boolean inBatch = owned.context().taskIds().contains(review.sourceTaskId());
            String outcome = review.decision();
            if ("WAIT_FOR_CHANGE".equals(review.decision())) {
                access.requestStop(owned.attempt(), "前置成果等待变化");
                response.put("stopRequested", true);
            } else if ("REQUEST_REWORK".equals(review.decision())) {
                if (inBatch) {
                    boolean reopened = inputs.reopenForRework(owned.context().projectId(), review.sourceTaskId(), userId);
                    if (reopened) outcome = "REWORK_REQUESTED";
                    else {
                        if (review.observations().isEmpty())
                            throw new IllegalArgumentException("等待成果变化需要至少一个待观察路径，请读取相关材料后重试");
                        access.requestStop(owned.attempt(), "前置成果等待变化");
                        outcome = "WAIT_FOR_CHANGE";
                    }
                    response.put("stopRequested", true);
                } else {
                    requireText(review.goal());
                    requireText(review.impact());
                    access.requestStop(owned.attempt(), "前置成果需业务授权");
                    outcome = "GOAL_AUTHORIZATION_REQUIRED";
                    response.put("stopRequested", true).put("goal", review.goal()).put("impact", review.impact())
                            .put("guidance", "当前目标内工作由 Agent 在本任务解决；新增业务目标经授权后进入新批次");
                }
            } else response.put("stopRequested", false);
            response.put("outcome", outcome);
            ObjectNode fact = json.createObjectNode().put("kind", KIND).put("operationId", command.operationId())
                    .put("agentInstanceId", command.agentInstanceId()).put("occurredAt", LocalDateTime.now().toString())
                    .put("outcome", outcome);
            fact.set("review", json.valueToTree(review));
            append(owned.attempt(), fact);
            response.set("review", json.valueToTree(review));
        }
        operations.save(owned.context().projectId(), userId, command.operationId(), "prerequisite_review",
                command.attemptId(), request, response);
        return response;
    }

    /** 执行详情从原检查点投影复核事实。 */
    public List<ReviewFact> project(TaskExecutionAttempt attempt) {
        List<ReviewFact> result = new ArrayList<>();
        JsonNode points = attempt.getCheckpoints();
        if (points == null || !points.isArray()) return List.of();
        for (JsonNode point : points) {
            if (KIND.equals(point.path("kind").asText()))
                result.add(new ReviewFact(point.path("operationId").asText(), point.path("agentInstanceId").asText(),
                        point.path("occurredAt").asText(), point.path("outcome").asText(), parseReview(point.path("review"))));
        }
        return List.copyOf(result);
    }

    public record ReviewFact(String operationId, String agentInstanceId, String occurredAt, String outcome,
                             PrerequisiteReviewRequest review) { }

    /** 当前观测缺失时不将原阻塞判定为事实变化。 */
    public String blockingReason(String batchId, Task task, List<TaskCollaborationInputService.PrerequisiteResult> prerequisites,
                                 List<PrerequisiteSourceObservation> sourceObservations) {
        validateSourceObservations(sourceObservations);
        List<TaskExecutionAttempt> history = attempts.selectByTaskId(task.getId());
        Map<Long, TaskCollaborationInputService.PrerequisiteResult> sources = new HashMap<>();
        for (var source : prerequisites) sources.put(source.taskId(), source);
        Map<String, PrerequisiteSourceObservation> observed = new HashMap<>();
        for (var source : sourceObservations) observed.put(source.sourceAttemptId() + ":" + source.sourceInputRevision(), source);
        Set<Long> evaluatedReviews = new HashSet<>();
        for (int attemptIndex = history.size() - 1; attemptIndex >= 0; attemptIndex--) {
            TaskExecutionAttempt attempt = history.get(attemptIndex);
            if (!Objects.equals(attempt.getBatchId(), batchId) || !Objects.equals(attempt.getInputRevision(), task.getInputRevision())) continue;
            JsonNode points = attempt.getCheckpoints();
            if (points == null || !points.isArray()) continue;
            for (int pointIndex = points.size() - 1; pointIndex >= 0; pointIndex--) {
                JsonNode point = points.get(pointIndex);
                if (!KIND.equals(point.path("kind").asText())) continue;
                PrerequisiteReviewRequest review = parseReview(point.path("review"));
                if (!evaluatedReviews.add(review.sourceTaskId())) continue;
                if (!("GOAL_AUTHORIZATION_REQUIRED".equals(point.path("outcome").asText())
                        || "WAIT_FOR_CHANGE".equals(point.path("outcome").asText()))) continue;
                var source = sources.get(review.sourceTaskId());
                boolean wait = "WAIT_FOR_CHANGE".equals(point.path("outcome").asText());
                boolean traceSource = source != null && source.evidence().path("artifactObservations").isArray()
                        && !source.evidence().path("artifactObservations").isEmpty();
                if (wait && (source == null || (!traceSource
                        && (!Objects.equals(source.attemptId(), review.sourceAttemptId())
                        || !Objects.equals(source.inputRevision(), review.sourceInputRevision()))))) continue;
                boolean traceWait = false;
                if ("WAIT_FOR_CHANGE".equals(point.path("outcome").asText())
                        && attempt.getPrerequisiteVersions() != null && attempt.getPrerequisiteVersions().isArray()) {
                    for (JsonNode item : attempt.getPrerequisiteVersions()) {
                        if (item.path("taskId").asLong() == review.sourceTaskId()
                                && item.path("evidence").path("artifactObservations").isArray()
                                && !item.path("evidence").path("artifactObservations").isEmpty()) traceWait = true;
                    }
                }
                if (wait && traceSource) {
                    var current = observed.get(source.attemptId() + ":" + source.inputRevision());
                    if (hasNewVerifiedTrace(attempt, source, review, current)) continue;
                    return "前置成果需处理：" + review.finding();
                }
                if (traceWait) return "前置成果需处理：" + review.finding();
                if (source == null || !Objects.equals(source.attemptId(), review.sourceAttemptId())
                        || !Objects.equals(source.inputRevision(), review.sourceInputRevision())) continue;
                var present = observed.get(review.sourceAttemptId() + ":" + review.sourceInputRevision());
                if ("WAIT_FOR_CHANGE".equals(point.path("outcome").asText())
                        && hasNewVerifiedTrace(attempt, source, review, present)) continue;
                if (present == null || !relatedChanged(review.observations(), present.observations()))
                    return "前置成果需处理：" + review.finding();
            }
        }
        return null;
    }

    private boolean hasNewVerifiedTrace(TaskExecutionAttempt attempt,
            TaskCollaborationInputService.PrerequisiteResult source, PrerequisiteReviewRequest review,
            PrerequisiteSourceObservation current) {
        if (current == null || attempt.getPrerequisiteVersions() == null || !attempt.getPrerequisiteVersions().isArray()) return false;
        JsonNode saved = null;
        for (JsonNode item : attempt.getPrerequisiteVersions()) {
            if (item.path("taskId").asLong() == source.taskId()
                    && item.path("attemptId").asLong() == review.sourceAttemptId()
                    && item.path("inputRevision").asLong() == review.sourceInputRevision()) saved = item;
        }
        if (saved == null) return false;
        JsonNode oldOperations = saved.path("evidence").path("traceOperationIds");
        JsonNode newOperations = source.evidence().path("traceOperationIds");
        JsonNode facts = source.evidence().path("artifactObservations");
        if (!oldOperations.isObject() || !newOperations.isObject() || !facts.isArray()) return false;
        Map<String, ArtifactObservation> currentByPath = byPath(current.observations());
        for (ArtifactObservation reviewed : review.observations()) {
            String path = reviewed.path();
            String oldId = oldOperations.path(path).asText();
            String newId = newOperations.path(path).asText();
            if (newId.isBlank() || (oldId.equals(newId) && Objects.equals(source.attemptId(), review.sourceAttemptId()))) continue;
            ArtifactObservation observed = currentByPath.get(path);
            for (JsonNode fact : facts) {
                if (observed != null && "PRESENT".equals(observed.state())
                        && path.equals(fact.path("path").asText())
                        && observed.contentVersion().equals(fact.path("contentVersion").asText())) return true;
            }
        }
        return false;
    }

    private boolean relatedChanged(List<ArtifactObservation> before, List<ArtifactObservation> current) {
        Map<String, ArtifactObservation> now = byPath(current);
        for (ArtifactObservation old : before) if (!now.containsKey(old.path())) return false;
        for (ArtifactObservation old : before) if (!old.equals(now.get(old.path()))) return true;
        return false;
    }

    /** 采用待办与材料核验由同一来源状态投影；本机未核验时不推断内容有效。 */
    public static List<PrerequisiteReviewStatus> projectStatuses(Task task, Long bindingId,
            List<TaskCollaborationInputService.PrerequisiteResult> sources, List<TaskExecutionAttempt> history,
            List<PrerequisiteSourceObservation> observations) {
        validateSourceObservations(observations);
        TaskExecutionAttempt relevant = null;
        for (TaskExecutionAttempt attempt : history) {
            boolean running = "running".equals(attempt.getStatus()) && attempt.getStopRequestedAt() == null
                    && attempt.getStopConfirmedAt() == null;
            boolean delivered = "done".equalsIgnoreCase(task.getStatus()) && "succeeded".equals(attempt.getStatus())
                    && attempt.getStopConfirmedAt() != null && attempt.getResultEvidence() != null
                    && !attempt.getResultEvidence().isNull();
            if (Objects.equals(attempt.getBindingId(), bindingId) && Objects.equals(attempt.getInputRevision(), task.getInputRevision())
                    && (running || delivered)) relevant = attempt;
        }
        Map<Long, JsonNode> latest = relevant == null ? Map.of() : latestReviews(relevant);
        List<PrerequisiteReviewStatus> statuses = new ArrayList<>();
        for (var source : sources) {
            JsonNode fact = latest.get(source.taskId());
            PrerequisiteReviewRequest review = fact == null ? null : parseReview(fact.path("review"));
            PrerequisiteSourceObservation current = observations.stream().filter(o ->
                    Objects.equals(source.attemptId(), o.sourceAttemptId())
                            && Objects.equals(source.inputRevision(), o.sourceInputRevision())).findFirst().orElse(null);
            if (review == null || !"ADOPT".equals(fact.path("outcome").asText())
                    || !Objects.equals(source.attemptId(), review.sourceAttemptId())
                    || !Objects.equals(source.inputRevision(), review.sourceInputRevision())) {
                String reason = current != null && current.observations().stream().anyMatch(o -> "MISSING".equals(o.state()))
                        ? "相关产物缺失，等待复核" : "前置交付等待采用";
                statuses.add(new PrerequisiteReviewStatus(source.taskId(), source.attemptId(), source.inputRevision(),
                        true, "REVIEW_REQUIRED", reason));
                continue;
            }
            if (review.observations().isEmpty()) {
                statuses.add(new PrerequisiteReviewStatus(source.taskId(), source.attemptId(), source.inputRevision(),
                        false, "NOT_APPLICABLE", "采用交付说明，未声明本机产物"));
                continue;
            }
            Map<String, ArtifactObservation> now = current == null ? Map.of() : byPath(current.observations());
            boolean changed = false;
            boolean missing = false;
            for (ArtifactObservation prior : review.observations()) {
                ArtifactObservation observed = now.get(prior.path());
                if (observed == null) missing = true;
                else if (!prior.equals(observed)) changed = true;
            }
            String state = changed ? "REVIEW_REQUIRED" : missing ? "PENDING_CONFIRMATION" : "CURRENT";
            String reason = changed ? "采用的材料发生变化" : missing ? "采用的材料等待宿主核验" : "采用的读取事实已确认";
            statuses.add(new PrerequisiteReviewStatus(source.taskId(), source.attemptId(), source.inputRevision(),
                    changed, state, reason));
        }
        return List.copyOf(statuses);
    }

    private static Map<Long, JsonNode> latestReviews(TaskExecutionAttempt attempt) {
        Map<Long, JsonNode> latest = new LinkedHashMap<>();
        JsonNode points = attempt.getCheckpoints();
        if (points != null && points.isArray()) for (JsonNode point : points) {
            if (KIND.equals(point.path("kind").asText())) {
                JsonNode source = point.path("review").path("sourceTaskId");
                if (source.isIntegralNumber()) latest.put(source.longValue(), point);
            }
        }
        return latest;
    }

    /** 交付事实引用本次尝试最后的采用记录，并携带交付前的对应读取事实。 */
    public static void requireAdopted(TaskExecutionAttempt attempt, List<PrerequisiteAdoption> adoptions) {
        if (adoptions == null) throw new IllegalArgumentException("前置采用事实不能为空");
        JsonNode snapshot = attempt.getPrerequisiteVersions();
        if (snapshot == null || !snapshot.isArray()) throw new IllegalStateException("前置交付快照无效");
        Map<Long, JsonNode> latest = latestReviews(attempt);
        Map<String, PrerequisiteAdoption> byOperation = new HashMap<>();
        for (PrerequisiteAdoption adoption : adoptions) {
            if (adoption == null || adoption.reviewOperationId() == null || adoption.reviewOperationId().isBlank()
                    || byOperation.put(adoption.reviewOperationId(), adoption) != null)
                throw new IllegalArgumentException("前置采用事实无效");
            validateObservations(adoption.observations());
        }
        if (byOperation.size() != snapshot.size()) throw new IllegalArgumentException("交付缺少前置成果采用依据");
        for (JsonNode source : snapshot) {
            JsonNode fact = latest.get(source.path("taskId").longValue());
            if (fact == null || !"ADOPT".equals(fact.path("outcome").asText()))
                throw new IllegalArgumentException("前置成果尚未采用");
            PrerequisiteReviewRequest review = parseReview(fact.path("review"));
            if (!Objects.equals(review.sourceAttemptId(), source.path("attemptId").longValue())
                    || !Objects.equals(review.sourceInputRevision(), source.path("inputRevision").longValue()))
                throw new IllegalArgumentException("采用的前置成果版本已变化");
            PrerequisiteAdoption adoption = byOperation.remove(fact.path("operationId").asText());
            if (adoption == null || !adoptionMatches(attempt, fact.path("operationId").asText(),
                    review.observations(), adoption.observations()))
                throw new IllegalArgumentException("前置成果读取事实已变化，请重新复核");
        }
        if (!byOperation.isEmpty()) throw new IllegalArgumentException("前置采用事实不属于当前尝试");
    }

    private static boolean adoptionMatches(TaskExecutionAttempt attempt, String reviewOperationId,
            List<ArtifactObservation> reviewed, List<ArtifactObservation> current) {
        Map<String, ArtifactObservation> old = byPath(reviewed);
        Map<String, ArtifactObservation> now = byPath(current);
        if (old.size() != now.size()) return false;
        Set<String> matched = new HashSet<>();
        for (var entry : old.entrySet()) {
            ArtifactObservation initial = entry.getValue();
            if (!"PRESENT".equals(initial.state())) {
                if (!initial.equals(now.get(entry.getKey())) || !matched.add(entry.getKey())) return false;
                continue;
            }
            String path = entry.getKey();
            String version = initial.contentVersion();
            boolean afterReview = false;
            JsonNode checkpoints = attempt.getCheckpoints();
            if (checkpoints == null || !checkpoints.isArray()) return false;
            for (JsonNode point : checkpoints) {
                if (!afterReview) {
                    if ("PREREQUISITE_REVIEW".equals(point.path("kind").asText())
                            && reviewOperationId.equals(point.path("operationId").asText())) afterReview = true;
                    continue;
                }
                JsonNode observations = point.path("artifactObservations");
                if (!observations.isArray() || observations.size() != 1) continue;
                String nextPath = observations.get(0).path("path").asText();
                String previousPath = point.path("previousPath").isTextual() ? point.path("previousPath").asText() : nextPath;
                if (!path.equals(previousPath)) continue;
                if (!version.equals(point.path("previousContentVersion").asText())
                        || !"PRESENT".equals(observations.get(0).path("state").asText())) return false;
                path = nextPath;
                version = observations.get(0).path("contentVersion").asText();
            }
            ArtifactObservation actual = now.get(path);
            if (!afterReview || actual == null || !"PRESENT".equals(actual.state())
                    || !version.equals(actual.contentVersion()) || !matched.add(path)) return false;
        }
        return true;
    }

    private static boolean previouslyAdoptedAndEdited(TaskExecutionAttempt attempt,
            PrerequisiteReviewRequest requested, ArtifactObservation current) {
        JsonNode checkpoints = attempt.getCheckpoints();
        if (checkpoints == null || !checkpoints.isArray()) return false;
        for (int index = checkpoints.size() - 1; index >= 0; index--) {
            JsonNode point = checkpoints.get(index);
            if (!KIND.equals(point.path("kind").asText()) || !"ADOPT".equals(point.path("outcome").asText())) continue;
            PrerequisiteReviewRequest previous = parseReview(point.path("review"));
            if (!Objects.equals(previous.sourceTaskId(), requested.sourceTaskId())
                    || !Objects.equals(previous.sourceAttemptId(), requested.sourceAttemptId())
                    || !Objects.equals(previous.sourceInputRevision(), requested.sourceInputRevision())) continue;
            for (ArtifactObservation old : previous.observations()) {
                if (adoptionMatches(attempt, point.path("operationId").asText(), List.of(old), List.of(current))) return true;
            }
        }
        return false;
    }

    public static void validateSourceObservations(List<PrerequisiteSourceObservation> sources) {
        if (sources == null) throw new IllegalArgumentException("前置来源观察不能为空");
        Set<String> keys = new HashSet<>();
        for (PrerequisiteSourceObservation source : sources) {
            if (source == null || source.sourceAttemptId() == null || source.sourceAttemptId() <= 0
                    || source.sourceInputRevision() == null || source.sourceInputRevision() <= 0
                    || !keys.add(source.sourceAttemptId() + ":" + source.sourceInputRevision()))
                throw new IllegalArgumentException("前置来源观察无效");
            validateObservations(source.observations());
        }
    }

    private static void validateReview(PrerequisiteReviewRequest review) {
        if (review == null || review.sourceTaskId() == null || review.sourceTaskId() <= 0
                || review.sourceAttemptId() == null || review.sourceAttemptId() <= 0
                || review.sourceInputRevision() == null || review.sourceInputRevision() <= 0
                || !("ADOPT".equals(review.decision()) || "REPAIR_IN_SCOPE".equals(review.decision())
                || "REQUEST_REWORK".equals(review.decision()) || "WAIT_FOR_CHANGE".equals(review.decision()))) throw new IllegalArgumentException("前置复核参数无效");
        requireText(review.finding()); requireText(review.evidence());
        validateObservations(review.observations());
        if ("WAIT_FOR_CHANGE".equals(review.decision()) && review.observations().isEmpty())
            throw new IllegalArgumentException("等待成果变化需要至少一个待观察路径，请读取相关材料后重试");
    }

    public static void validateObservations(List<ArtifactObservation> observations) {
        if (observations == null) throw new IllegalArgumentException("产物读取事实不能为空");
        Set<String> paths = new HashSet<>();
        for (ArtifactObservation item : observations) {
            if (item == null || item.path() == null
                    || !("PRESENT".equals(item.state()) || "MISSING".equals(item.state())))
                throw new IllegalArgumentException("产物读取事实无效");
            if (("PRESENT".equals(item.state()) && (item.contentVersion() == null || !item.contentVersion().matches("sha256:[0-9a-f]{64}")))
                    || ("MISSING".equals(item.state()) && item.contentVersion() != null))
                throw new IllegalArgumentException("产物内容版本无效");
            ProjectArtifactPathService.validatePath(item.path());
            // 历史复核可分别观察同名的文件和目录，保留末尾斜线以匹配各自的事实。
            if (!paths.add(item.path())) throw new IllegalArgumentException("产物读取路径不能重复");
        }
    }

    private static Map<String, ArtifactObservation> byPath(List<ArtifactObservation> observations) {
        Map<String, ArtifactObservation> result = new HashMap<>();
        for (ArtifactObservation item : observations) result.put(item.path(), item);
        return result;
    }

    private static PrerequisiteReviewRequest parseReview(JsonNode value) {
        try { return FACT_JSON.treeToValue(value, PrerequisiteReviewRequest.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("前置复核事实解析失败", e); }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank() || value.length() > 2048) throw new IllegalArgumentException("前置复核依据不完整");
    }

    private void append(TaskExecutionAttempt attempt, ObjectNode fact) {
        JsonNode previous = attempt.getCheckpoints();
        if (previous == null || !previous.isArray()) throw new IllegalStateException("检查点数据无效");
        try {
            if (attempts.appendCheckpoint(attempt.getId(), previous.size(), json.writeValueAsString(fact)) != 1)
                throw new IllegalStateException("前置复核事实保存失败");
        } catch (JsonProcessingException e) { throw new IllegalStateException("前置复核事实序列化失败", e); }
    }
}
