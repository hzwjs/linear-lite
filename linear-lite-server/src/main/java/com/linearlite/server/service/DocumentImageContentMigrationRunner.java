package com.linearlite.server.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 启动时执行存量正文图片身份迁移；发现无法解析的数据时中断启动，避免带病发布。 */
@Component
@ConditionalOnProperty(prefix = "app.document-image", name = "migrate-on-startup", havingValue = "true")
public class DocumentImageContentMigrationRunner implements ApplicationRunner {

    private final DocumentImageContentMigrationService migrationService;

    public DocumentImageContentMigrationRunner(DocumentImageContentMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        DocumentImageContentMigrationService.MigrationReport report = migrationService.migrate();
        if (report.aborted()) {
            throw new IllegalStateException(
                    "文档图片迁移中止，存在无法解析的数据: " + String.join("; ", report.errors()));
        }
    }
}
