package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("project_task_seq")
public class ProjectTaskSeq {

    @TableId
    private Long projectId;
    private Long nextNumber;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getNextNumber() {
        return nextNumber;
    }

    public void setNextNumber(Long nextNumber) {
        this.nextNumber = nextNumber;
    }
}
