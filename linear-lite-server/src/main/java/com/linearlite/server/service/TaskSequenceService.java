package com.linearlite.server.service;

import com.linearlite.server.entity.ProjectTaskSeq;
import com.linearlite.server.mapper.ProjectTaskSeqMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class TaskSequenceService {

    private final ProjectTaskSeqMapper projectTaskSeqMapper;

    public TaskSequenceService(ProjectTaskSeqMapper projectTaskSeqMapper) {
        this.projectTaskSeqMapper = projectTaskSeqMapper;
    }

    public long reserveTaskNumbers(Long projectId, String identifier, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount 必须大于 0");
        }
        ProjectTaskSeq seq = projectTaskSeqMapper.selectByProjectIdForUpdate(projectId);
        if (seq == null) {
            long initialNext = loadInitialNextTaskNumber(projectId, identifier);
            try {
                projectTaskSeqMapper.insertRow(projectId, initialNext);
            } catch (DuplicateKeyException ignored) {
                // 并发下已有事务插入成功，重读加锁行即可
            }
            seq = projectTaskSeqMapper.selectByProjectIdForUpdate(projectId);
            if (seq == null) {
                throw new IllegalStateException("初始化项目任务序号失败: " + projectId);
            }
        }
        long start = seq.getNextNumber();
        projectTaskSeqMapper.updateNextNumber(projectId, start + amount);
        return start;
    }

    private long loadInitialNextTaskNumber(Long projectId, String identifier) {
        int startPos = identifier.length() + 2;
        Long max = projectTaskSeqMapper.selectMaxTaskNumber(projectId, identifier, startPos);
        long next = (max == null ? 0 : max) + 1;
        return Math.max(next, 1);
    }
}
