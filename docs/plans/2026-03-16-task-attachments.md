# Task Attachments Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a task-level attachment list: upload via paperclip button, any file type up to 10MB, store in R2 with metadata in `task_attachments`, list/download/delete in task detail.

**Architecture:** New table `task_attachments`; backend extends ObjectStorageService with attachment upload (key prefix `attachments/{taskId}/`) and optional deleteObject; TaskAttachmentService resolves task by taskKey, enforces project membership, then CRUD attachments; frontend paperclip triggers file input, POST per file, GET list on open, Attachments section between content-actions and Sub-issues.

**Tech Stack:** Vue 3, TypeScript, Spring Boot 3, MyBatis-Plus, R2/S3 SDK, multipart upload

---

### Task 1: Add schema and entity for task_attachments

**Files:**
- Create: `linear-lite-server/src/main/resources/schema-v10-task-attachments.sql`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/entity/TaskAttachment.java`

**Step 1: Create migration**

Add `schema-v10-task-attachments.sql`:

```sql
USE linear_lite;

CREATE TABLE IF NOT EXISTS task_attachments (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id      BIGINT       NOT NULL,
    object_key   VARCHAR(512) NOT NULL,
    file_name    VARCHAR(256) NOT NULL,
    file_size    BIGINT       NOT NULL,
    content_type VARCHAR(128) DEFAULT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_task_attachments_task_id ON task_attachments (task_id);
```

**Step 2: Create entity**

Add `TaskAttachment.java` with fields: `id`, `taskId`, `objectKey`, `fileName`, `fileSize`, `contentType`, `createdAt`; use `@TableName("task_attachments")`, `@TableId(type = IdType.AUTO)` for `id`. Match style of `TaskActivity.java`.

**Step 3: Commit**

```bash
git add linear-lite-server/src/main/resources/schema-v10-task-attachments.sql linear-lite-server/src/main/java/com/linearlite/server/entity/TaskAttachment.java
git commit -m "feat: add task_attachments schema and entity"
```

---

### Task 2: Add TaskAttachmentMapper

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskAttachmentMapper.java`

**Step 1: Create mapper**

Extend `BaseMapper<TaskAttachment>`, same pattern as `TaskActivityMapper` / `TaskMapper`. No XML.

**Step 2: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskAttachmentMapper.java
git commit -m "feat: add TaskAttachmentMapper"
```

---

### Task 3: Extend storage layer for attachment upload and delete

**Files:**
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/service/R2StorageClient.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/service/S3R2StorageClient.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/service/ObjectStorageService.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/service/R2ObjectStorageService.java`

**Step 1: Add deleteObject to R2StorageClient**

```java
void deleteObject(String bucket, String key);
```

**Step 2: Implement deleteObject in S3R2StorageClient**

Use `s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())`. Add `DeleteObjectRequest` import from `software.amazon.awssdk.services.s3.model`.

**Step 3: Add attachment methods to ObjectStorageService**

- `ImageUploadResponse uploadAttachment(MultipartFile file, long taskId);` — any file type, max 10MB, key `attachments/{taskId}/{uuid}-{sanitizedFilename}`.
- `void deleteObjectByKey(String key);` — delete from R2 (bucket from properties). Optional: if R2 not enabled, no-op or throw; plan assumes R2 enabled for attachments.

**Step 4: Implement in R2ObjectStorageService**

- `uploadAttachment`: reject empty; reject size > 10MB; use existing `sanitizeFilename`; key = `"attachments/" + taskId + "/" + UUID.randomUUID() + "-" + sanitizeFilename(originalFilename)`; contentType from file or `application/octet-stream`; putObject then return ImageUploadResponse(url, key).
- `deleteObjectByKey`: call `storageClient.deleteObject(properties.getBucket(), key)`. On failure log and rethrow or swallow per design (design: log and still return 204 from controller).
- Expose `sanitizeFilename` as package-private or reuse existing logic (it's private static today — extract to a shared private method used by both buildObjectKey and uploadAttachment).

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/service/R2StorageClient.java S3R2StorageClient.java ObjectStorageService.java R2ObjectStorageService.java
git commit -m "feat: add attachment upload and object delete to storage layer"
```

---

### Task 4: Add attachment DTO and TaskAttachmentService

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/TaskAttachmentResponse.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/TaskAttachmentService.java`

**Step 1: Create response DTO**

`TaskAttachmentResponse`: `id`, `taskId`, `objectKey`, `fileName`, `fileSize`, `contentType`, `url`, `createdAt` (ISO string or long). Constructor or factory from `TaskAttachment` + `url` (injected from storage base URL + key).

**Step 2: Add TaskService.getByKeyOrThrow**

In `TaskService`: add `public Task getByKeyOrThrow(String taskKey, Long userId)`: selectOne by task_key; if null throw ResourceNotFoundException; call existing `requireProjectMember(task.getProjectId(), userId)` (make it package-private or add a public overload that takes task); return task.

**Step 3: Create TaskAttachmentService**

- Dependencies: TaskService, TaskAttachmentMapper, ObjectStorageService, R2StorageProperties.
- `upload(String taskKey, MultipartFile file, Long userId)`: `Task task = taskService.getByKeyOrThrow(taskKey, userId)`; call objectStorageService.uploadAttachment(file, task.getId()); insert TaskAttachment; return TaskAttachmentResponse with url from upload result.
- `listByTaskKey(String taskKey, Long userId)`: taskService.getByKeyOrThrow(taskKey, userId); list by task_id order by created_at; map to TaskAttachmentResponse with url = R2StorageProperties.getPublicBaseUrl().replaceAll("/+$", "") + "/" + att.getObjectKey().
- `delete(String taskKey, Long attachmentId, Long userId)`: getByKeyOrThrow; get attachment by id and task_id; if null 404; delete from DB; objectStorageService.deleteObjectByKey(att.getObjectKey()); on failure log only.

**Step 4: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/dto/TaskAttachmentResponse.java linear-lite-server/src/main/java/com/linearlite/server/service/TaskAttachmentService.java linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java
git commit -m "feat: add TaskAttachmentResponse, TaskAttachmentService, TaskService.getByKeyOrThrow"
```

---

### Task 5: Add attachment endpoints and handle R2 disabled

**Files:**
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java`

**Step 1: Add attachment endpoints in TaskController**

- `POST /api/tasks/{taskKey}/attachments`: @RequestParam("file") MultipartFile file; get userId from request; if objectStorageService is null (optional bean) return 503 with message "存储服务不可用"; else call taskAttachmentService.upload(taskKey, file, userId); return 200 with body ApiResponse.success(attachmentResponse).
- `GET /api/tasks/{taskKey}/attachments`: list taskAttachmentService.listByTaskKey(taskKey, userId); return 200 with ApiResponse.success(list).
- `DELETE /api/tasks/{taskKey}/attachments/{attachmentId}`: taskAttachmentService.delete(taskKey, attachmentId, userId); return 204.

Inject TaskAttachmentService; for POST inject ObjectStorageService as Optional<ObjectStorageService> and if empty return 503.

**Step 2: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java
git commit -m "feat: add task attachment REST endpoints"
```

---

### Task 6: Backend tests for attachment upload and list

**Files:**
- Create: `linear-lite-server/src/test/java/com/linearlite/server/service/TaskAttachmentServiceTest.java`
- Or extend: `linear-lite-server/src/test/java/com/linearlite/server/controller/TaskController.java` (if controller test exists) with attachment endpoints

**Step 1: Write tests**

- TaskAttachmentServiceTest: mock TaskMapper, ProjectMemberMapper, TaskAttachmentMapper, ObjectStorageService, R2StorageProperties; test upload success (task exists, member, file valid -> insert and return response); list (member -> returns list); delete (member, attachment belongs to task -> delete DB and call deleteObjectByKey); upload when not member -> ForbiddenOperationException; delete wrong task attachment -> 404.
- Or controller integration tests: POST with file returns 200; GET returns list; DELETE returns 204; POST when not member 403; GET when task not found 404.

**Step 2: Run tests**

Run: `cd linear-lite-server && mvn test -Dtest=TaskAttachmentServiceTest`
Expected: PASS.

**Step 3: Commit**

```bash
git add linear-lite-server/src/test/java/com/linearlite/server/service/TaskAttachmentServiceTest.java
git commit -m "test: add TaskAttachmentService tests"
```

---

### Task 7: Frontend API client for attachments

**Files:**
- Create: `src/services/api/attachments.ts`
- Modify: `src/services/api/types.ts` (add Attachment type and list response if needed)
- Modify: `src/services/api/index.ts` (export attachmentsApi if central exports used)

**Step 1: Add types**

In `types.ts` or in `attachments.ts`: `TaskAttachment` or `Attachment`: `id`, `taskId`, `fileName`, `fileSize`, `contentType`, `url`, `createdAt`. List response: `ApiResponse<Attachment[]>`.

**Step 2: Implement attachmentsApi**

- `upload(taskKey: string, file: File): Promise<Attachment>` — FormData with key `file`, POST `/tasks/${taskKey}/attachments` with headers `{ 'Content-Type': 'multipart/form-data' }` (or let browser set); unwrap and return.
- `list(taskKey: string): Promise<Attachment[]>` — GET `/tasks/${taskKey}/attachments`, unwrap.
- `delete(taskKey: string, attachmentId: number): Promise<void>` — DELETE `/tasks/${taskKey}/attachments/${attachmentId}`, no body; 204 no content.

Use `api` from `./index` (axios instance with JWT). For upload do not set Content-Type so browser sets multipart boundary.

**Step 3: Export from index if needed**

If `src/services/api/index.ts` re-exports api modules, add `export * from './attachments'` or export `attachmentsApi`.

**Step 4: Commit**

```bash
git add src/services/api/attachments.ts src/services/api/types.ts src/services/api/index.ts
git commit -m "feat: add attachments API client"
```

---

### Task 8: TaskEditor — paperclip and file input

**Files:**
- Modify: `src/components/TaskEditor.vue`

**Step 1: Add ref and hidden input**

- Ref for file input: `attachmentInputRef`; hidden `<input ref="attachmentInputRef" type="file" multiple accept="*" style="display:none" @change="onAttachmentInputChange">`. Place inside editor content area (e.g. near content-actions).

**Step 2: Wire paperclip button**

- On paperclip click: if `mode === 'edit' && task` then `attachmentInputRef?.click()`. Disable or hide when not edit or no task.

**Step 3: Implement onAttachmentInputChange**

- Get `files` from event; if empty return; for each file check size <= 10MB (skip or toast if over); call attachmentsApi.upload(task.id, file) (task.id is task_key in this codebase — confirm: Task type uses `id` for task_key). After each success push to local attachments list or refetch list; on failure toast and optionally add placeholder with retry. For minimal v1: sequential upload, on success refetch list; on failure toast only.

**Step 4: Commit**

```bash
git add src/components/TaskEditor.vue
git commit -m "feat: wire paperclip to file input and upload attachments"
```

---

### Task 9: TaskEditor — attachments section and list

**Files:**
- Modify: `src/components/TaskEditor.vue`

**Step 1: Data and fetch**

- State: `attachments: ref<Attachment[]>([])`, `attachmentsLoading: ref(false)`. When `mode === 'edit' && task` and task changes, fetch: `attachmentsApi.list(task.id).then(r => attachments.value = r).catch(...)`. Reset attachments when task is null or mode create.

**Step 2: Attachments section UI**

- Between content-actions and Sub-issues section: new section `content-section subdued linear-section`, only when `mode === 'edit' && task`. Header: "Attachments" with count `attachments.length`. Collapsible like Sub-issues (optional: collapsed by default or expanded). Body: if attachments.length === 0 show "No attachments. Use the paperclip to add one." Else `<ul>` of items: fileName (link to url), fileSize (formatted), createdAt (relative or short date), download link, delete button. Delete calls attachmentsApi.delete(task.id, att.id) then remove from local list or refetch.

**Step 3: Format helpers**

- fileSize: human-readable (e.g. 1.2 MB); createdAt: relative time or locale string.

**Step 4: Commit**

```bash
git add src/components/TaskEditor.vue
git commit -m "feat: add Attachments section and list with download/delete"
```

---

### Task 10: Upload placeholder and error handling (optional v1)

**Files:**
- Modify: `src/components/TaskEditor.vue`

**Step 1: Placeholder while uploading**

- When user selects files, add temporary items to a separate `uploadingAttachments: ref<{ file: File, localId: string }[]>([])` and show "Uploading…" for each; on success remove from uploading and add to attachments (or refetch); on failure show "Upload failed" with Remove button (and optional Retry). Use a simple localId (e.g. timestamp + index) for key.

**Step 2: 503 handling**

- If upload returns 503 or message indicates storage unavailable, toast and optionally disable paperclip until next open.

**Step 3: Commit**

```bash
git add src/components/TaskEditor.vue
git commit -m "feat: attachment upload placeholders and error handling"
```

---

### Task 11: Run backend and frontend tests

**Step 1: Backend**

Run: `cd linear-lite-server && mvn test`
Expected: all tests pass.

**Step 2: Frontend**

Run: `npm test` or `npm run test:unit`
Expected: pass. Fix any failures from new code or types.

**Step 3: Commit if any fixes**

---

## Execution handoff

Plan complete and saved to `docs/plans/2026-03-16-task-attachments.md`.

Two execution options:

1. **Subagent-Driven (this session)** — Dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Parallel Session (separate)** — Open a new session with executing-plans, batch execution with checkpoints.

Which approach?
