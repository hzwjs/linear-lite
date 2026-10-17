package com.linearlite.server.service;

import com.linearlite.server.config.CollaborationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.util.List;

@Service
public class CollaborationNodeClient {
    public static final String INTERNAL_KEY_HEADER = "X-Collaboration-Internal-Key";

    private final CollaborationProperties properties;
    private final RestClient client;

    public CollaborationNodeClient(RestClient.Builder builder, CollaborationProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        this.client = builder.requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .baseUrl(properties.getServerUrl()).build();
    }

    public EncodeResponse encode(String contentFormat, String content) {
        return post("/internal/collaboration/encode", new EncodeRequest(contentFormat, content), EncodeResponse.class);
    }

    public SnapshotResponse snapshot(Long documentId, String epoch, boolean flush) {
        return client.get()
                .uri("/internal/collaboration/documents/{documentId}/snapshot?epoch={epoch}&flush={flush}",
                        documentId, epoch, flush)
                .header(INTERNAL_KEY_HEADER, internalKey())
                .retrieve()
                .body(SnapshotResponse.class);
    }

    public CommandResponse command(Long documentId, CommandRequest request) {
        return post("/internal/collaboration/documents/" + documentId + "/commands", request,
                CommandResponse.class);
    }

    public void publishMetadata(Long documentId, MetadataEvent event) {
        post("/internal/collaboration/documents/" + documentId + "/metadata", event, Void.class);
    }

    public CloseRoomResponse closeRoom(Long documentId, CloseRoomRequest request) {
        return post("/internal/collaboration/documents/" + documentId + "/close-room",
                request, CloseRoomResponse.class);
    }

    public void releaseTransition(TransitionReleaseRequest request) {
        post("/internal/collaboration/transitions/release", request, Void.class);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        return client.post().uri(path)
                .header(INTERNAL_KEY_HEADER, internalKey())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .retrieve()
                .body(responseType);
    }

    private String internalKey() {
        String key = properties.getInternalKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("COLLABORATION_INTERNAL_KEY 未配置");
        }
        return key;
    }

    public record EncodeRequest(String contentFormat, String content) { }
    public record EncodeResponse(String stateBase64, String stateSha256, String content, Long contentBytes,
                                 List<Long> imageAssetIds) { }
    public record SnapshotResponse(String epoch, Long version, String contentFormat, String content) { }
    public record CommandRequest(String operationId, Long actorId, String epoch, Long expectedVersion,
                                 String type, String contentFormat, String content, String title) { }
    public record CommandResponse(String epoch, Long commitSeq, Long version, String contentFormat,
                                  String content, String title) { }
    public record MetadataEvent(String epoch, Long version, String title, Long editorId) { }
    public record CloseRoomRequest(String epoch, String nextEpoch, String operation,
                                   String transitionId, Long deadlineEpochMs) { }
    public record CloseRoomResponse(String epoch, Long commitSeq, Long version, String transitionId) { }
    public record TransitionReleaseRequest(String transitionId, String status,
                                           List<TransitionDocumentRelease> documents) { }
    public record TransitionDocumentRelease(Long documentId, String epoch) { }
}
