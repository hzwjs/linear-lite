package com.linearlite.server.dto.codex;

import java.time.LocalDateTime;
import java.util.List;

/** Codex 派发接口的固定 DTO，Runner 不接收任意文件系统路径。 */
public final class CodexDtos {
    private CodexDtos() {}
    public record EnrollmentCodeResponse(String code, LocalDateTime expiresAt) {}
    public record RegisterRequest(String enrollmentCode, String name) {}
    public record RegisterResponse(Long runnerId, String runnerToken) {}
    public record RepositoryInput(String repositoryKey, String displayName, String remoteIdentity, String defaultBranch) {}
    public record HeartbeatRequest(String version, List<RepositoryInput> repositories) {}
    public record BindingRequest(Long runnerId, Long repositoryId, String baseBranch) {}
    public record DispatchRequest(String clientRequestId, String instruction) {}
    public record MessageRequest(String content) {}
    public record ThreadRequest(String codexThreadId) {}
    public record EventRequest(Long sequenceNo, String eventType, String eventPayload) {}
    public record CompleteRequest(String status, String resultSummary, String resultPayload, String errorCode, String errorMessage) {}
    public record RunnerMessageResponse(Long id, String content) {}
}
