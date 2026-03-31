package com.linearlite.server.dto;

/**
 * 趋势分桶：每个时间桶同时统计创建/完成/到期三个口径。
 */
public class TrendBucket {

    private String bucketStart;
    private String bucketEnd;
    private int createdCount;
    private int completedCount;
    private int dueCount;

    public TrendBucket() {
    }

    public TrendBucket(String bucketStart, String bucketEnd, int createdCount, int completedCount, int dueCount) {
        this.bucketStart = bucketStart;
        this.bucketEnd = bucketEnd;
        this.createdCount = createdCount;
        this.completedCount = completedCount;
        this.dueCount = dueCount;
    }

    public String getBucketStart() {
        return bucketStart;
    }

    public void setBucketStart(String bucketStart) {
        this.bucketStart = bucketStart;
    }

    public String getBucketEnd() {
        return bucketEnd;
    }

    public void setBucketEnd(String bucketEnd) {
        this.bucketEnd = bucketEnd;
    }

    public int getCreatedCount() {
        return createdCount;
    }

    public void setCreatedCount(int createdCount) {
        this.createdCount = createdCount;
    }

    public int getCompletedCount() {
        return completedCount;
    }

    public void setCompletedCount(int completedCount) {
        this.completedCount = completedCount;
    }

    public int getDueCount() {
        return dueCount;
    }

    public void setDueCount(int dueCount) {
        this.dueCount = dueCount;
    }
}
