package com.linearlite.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 启动时为存量图片回填尺寸与缩略图；显式开启，避免每次启动重复扫描。 */
@Component
@ConditionalOnProperty(prefix = "app.document-image", name = "backfill-on-startup", havingValue = "true")
public class DocumentImageMetadataBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentImageMetadataBackfillRunner.class);

    private final DocumentImageMetadataBackfillService backfillService;

    public DocumentImageMetadataBackfillRunner(DocumentImageMetadataBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int updated = backfillService.backfill();
        log.info("存量文档图片回填完成：更新 {} 张", updated);
    }
}
