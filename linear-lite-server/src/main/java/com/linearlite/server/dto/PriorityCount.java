package com.linearlite.server.dto;

public class PriorityCount {

    private String priority;
    private int count;

    public PriorityCount() {
    }

    public PriorityCount(String priority, int count) {
        this.priority = priority;
        this.count = count;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}
