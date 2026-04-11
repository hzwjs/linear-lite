package com.linearlite.server.config;

import com.linearlite.server.dto.ImageUploadResponse;
import com.linearlite.server.service.ObjectStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
public class R2StorageConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.storage.r2", name = "enabled", havingValue = "true")
    public S3Client r2S3Client(R2StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKeyId(), properties.getSecretAccessKey())
                ))
                .region(Region.of(properties.getRegion()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(ObjectStorageService.class)
    public ObjectStorageService disabledObjectStorageService() {
        return new ObjectStorageService() {
            private void throwDisabled() {
                throw new IllegalStateException("R2 storage is not configured");
            }

            @Override
            public ImageUploadResponse uploadImage(MultipartFile file) {
                throwDisabled();
                return null;
            }

            @Override
            public ImageUploadResponse uploadAttachment(MultipartFile file, long taskId) {
                throwDisabled();
                return null;
            }

            @Override
            public java.io.InputStream openObjectStreamByKey(String key) {
                throwDisabled();
                return null;
            }

            @Override
            public void deleteObjectByKey(String key) {
                throwDisabled();
            }
        };
    }
}
