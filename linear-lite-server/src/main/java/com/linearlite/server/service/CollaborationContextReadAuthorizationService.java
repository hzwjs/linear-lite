package com.linearlite.server.service;

import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 读取快照前释放项目锁，避免文档节点刷盘回调等待相同锁。 */
@Service
public class CollaborationContextReadAuthorizationService {
    private final TaskExecutionAccessService access;
    public CollaborationContextReadAuthorizationService(TaskExecutionAccessService access) { this.access = access; }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long authorize(Long userId, CollaborationBatchRequest batch, Long taskId, CollaborationAttemptCommand command) {
        var owned = access.requireOwnedAttempt(userId, batch, taskId, command);
        var check = access.checkWritable(owned, command);
        if (!check.accepted()) throw new IllegalStateException(check.reason());
        return owned.context().projectId();
    }
}
