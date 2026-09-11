package com.linearlite.server.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 定时回收未被正文/修订引用的文档图片附件；默认关闭，由部署方显式开启。 */
@Component
@ConditionalOnProperty(prefix = "app.document-image", name = "orphan-cleanup-enabled", havingValue = "true")
public class DocumentImageOrphanCleanupJob {

    private final DocumentImageOrphanCleanupService cleanupService;
    private final long minAgeHours;

    public DocumentImageOrphanCleanupJob(
            DocumentImageOrphanCleanupService cleanupService,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.document-image.orphan-cleanup-min-age-hours:24}") long minAgeHours) {
        this.cleanupService = cleanupService;
        this.minAgeHours = minAgeHours;
    }

    @Scheduled(
            fixedDelayString = "${app.document-image.orphan-cleanup-delay-millis:3600000}",
            initialDelayString = "${app.document-image.orphan-cleanup-initial-delay-millis:600000}")
    public void run() {
        cleanupService.cleanup(Duration.ofHours(Math.max(1, minAgeHours)));
    }
}
