package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.linearlite.server.dto.CreateTaskCommentRequest;
import com.linearlite.server.dto.TaskCommentResponse;
import com.linearlite.server.service.TaskCommentAttachmentService;
import com.linearlite.server.service.TaskCommentImageDraft;
import com.linearlite.server.service.TaskCommentService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;

@Service
public class McpTaskCommentImageContentService {
    private final MarkdownToBlockNoteConverter converter;
    private final TaskCommentAttachmentService attachments;
    private final TaskCommentService comments;

    public McpTaskCommentImageContentService(MarkdownToBlockNoteConverter converter,
            TaskCommentAttachmentService attachments, TaskCommentService comments) {
        this.converter = converter; this.attachments = attachments; this.comments = comments;
    }

    public TaskCommentResponse create(String taskKey, Long userId, String operationId, String body, Long parentId,
            List<Long> mentions, JsonNode inputImages) {
        Set<String> refs = converter.imageReferences(body);
        List<McpTaskCommentImageInput> inputs = parse(inputImages);
        Map<String, McpTaskCommentImageInput> byRef = new HashMap<>();
        inputs.forEach(image -> byRef.put(image.ref(), image));
        if (!byRef.keySet().equals(refs)) throw new McpInvalidParamsException("Markdown 图片引用与 images 不匹配");
        if (operationId == null || operationId.isBlank() || operationId.length() > 128)
            throw new McpInvalidParamsException("operationId 须为 1 至 128 个字符");
        String requestHash = requestHash(taskKey, body, parentId, mentions, inputs);
        CreateTaskCommentRequest request = new CreateTaskCommentRequest();
        request.setBody(body); request.setParentId(parentId); request.setMentionedUserIds(mentions);
        TaskCommentResponse replay = comments.findMcpReplay(taskKey, userId, operationId, requestHash);
        if (replay != null) return replay;
        List<TaskCommentImageDraft> drafts = new ArrayList<>();
        try {
            for (String ref : refs) {
                McpTaskCommentImageInput image = byRef.get(ref);
                drafts.add(image.isUpload()
                        ? attachments.upload(taskKey, userId, ref, image.fileName(), image.contentType(), image.content())
                        : attachments.reference(taskKey, userId, ref, image.attachmentId()));
            }
            TaskCommentResponse response = comments.createMcp(taskKey, userId, request, drafts, operationId, requestHash);
            attachments.cleanupUnbound(response.getId(), drafts);
            return response;
        } catch (RuntimeException e) {
            attachments.cleanup(drafts);
            throw e;
        }
    }

    private String requestHash(String taskKey, String body, Long parentId, List<Long> mentions,
            List<McpTaskCommentImageInput> images) {
        StringBuilder canonical = new StringBuilder(taskKey).append('\n').append(body.trim()).append('\n')
                .append(parentId == null ? "" : parentId).append('\n');
        if (mentions != null) mentions.stream().distinct().sorted().forEach(id -> canonical.append(id).append(','));
        images.stream().sorted(Comparator.comparing(McpTaskCommentImageInput::ref)).forEach(image -> {
            canonical.append('\n').append(image.ref()).append('|');
            if (image.isUpload()) canonical.append(image.fileName()).append('|').append(image.contentType()).append('|').append(sha256(image.content()));
            else canonical.append("attachment:").append(image.attachmentId());
        });
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 不可用", e); }
    }

    private List<McpTaskCommentImageInput> parse(JsonNode value) {
        if (value == null) return List.of();
        if (!value.isArray() || value.size() > 8) throw new McpInvalidParamsException("images 最多 8 项");
        List<McpTaskCommentImageInput> result = new ArrayList<>();
        Set<String> refs = new HashSet<>(); int total = 0; long totalBase64Chars = 0;
        for (JsonNode item : value) {
            if (!item.isObject()) throw new McpInvalidParamsException("images 元素须为对象");
            String ref = McpArgumentValidator.requiredText(item, "ref", 64);
            if (!ref.matches("[A-Za-z0-9_-]{1,64}") || !refs.add(ref)) throw new McpInvalidParamsException("图片 ref 必须唯一且有效");
            Long attachmentId = McpArgumentValidator.optionalLong(item, "attachmentId");
            if (attachmentId != null) {
                McpArgumentValidator.fields(item, "ref", "attachmentId");
                if (attachmentId <= 0) throw new McpInvalidParamsException("attachmentId 必须是正整数");
                result.add(new McpTaskCommentImageInput(ref, null, null, null, attachmentId)); continue;
            }
            McpArgumentValidator.fields(item, "ref", "fileName", "contentType", "dataBase64");
            String fileName = McpArgumentValidator.requiredText(item, "fileName", 255);
            String contentType = McpArgumentValidator.requiredText(item, "contentType", 128);
            JsonNode encoded = item.get("dataBase64");
            if (encoded == null || !encoded.isTextual() || encoded.textValue().length() > 8_388_608)
                throw new McpInvalidParamsException("dataBase64 无效或超过单图大小限制");
            totalBase64Chars += encoded.textValue().length();
            if (totalBase64Chars > 16_777_216) throw new McpInvalidParamsException("图片 Base64 总量不能超过 16 MiB");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(encoded.textValue()); }
            catch (IllegalArgumentException e) { throw new McpInvalidParamsException("dataBase64 必须是有效 Base64"); }
            total += bytes.length;
            if (bytes.length == 0 || bytes.length > 6 * 1024 * 1024 || total > 12 * 1024 * 1024)
                throw new McpInvalidParamsException("图片须单张不超过 6 MiB，总量不超过 12 MiB");
            result.add(new McpTaskCommentImageInput(ref, fileName, contentType, bytes, null));
        }
        return result;
    }
}
