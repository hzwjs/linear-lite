package com.linearlite.server.dto;

/**
 * 更新任务标签时数组元素：{@code id} 与 {@code name} 二选一由服务端校验。
 */
public class TaskLabelItemRequest {

    private Long id;
    private String name;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
