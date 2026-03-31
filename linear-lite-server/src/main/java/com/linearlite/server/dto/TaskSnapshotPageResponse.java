package com.linearlite.server.dto;

import java.util.List;

/**
 * 任务明细分页响应。
 */
public class TaskSnapshotPageResponse {

    private List<TaskSnapshotItem> items;
    private int total;
    private int page;
    private int pageSize;

    public TaskSnapshotPageResponse() {
    }

    public TaskSnapshotPageResponse(List<TaskSnapshotItem> items, int total, int page, int pageSize) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public List<TaskSnapshotItem> getItems() {
        return items;
    }

    public void setItems(List<TaskSnapshotItem> items) {
        this.items = items;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
