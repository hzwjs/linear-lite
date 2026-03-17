# R2 Task Description Images Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add paste and drag image upload for task descriptions by storing images in Cloudflare R2 and inserting the returned public URL into Markdown content.

**Architecture:** The frontend intercepts pasted or dropped image files in the shared Tiptap-based editor, validates them, uploads them through a new authenticated backend endpoint, then inserts Markdown image syntax into the description. The backend validates multipart uploads, stores objects in Cloudflare R2 through a storage service abstraction, and returns a public object URL built from configured base URL plus object key.

**Tech Stack:** Vue 3, TypeScript, Tiptap, Vitest, Spring Boot 3, Java multipart upload, AWS S3-compatible SDK for Cloudflare R2, JUnit 5, Mockito

---

### Task 1: Add frontend upload API contract

**Files:**
- Create: `src/services/api/upload.ts`
- Modify: `src/services/api/types.ts`
- Inspect: `src/services/api/index.ts`
- Test: `tests` or focused Vitest file for API helper if one exists

**Step 1: Write the failing test**

Add a focused test for the upload API helper that asserts:

- it sends `multipart/form-data`
- it returns an object containing `url` and `key`
- it surfaces backend failure messages

**Step 2: Run test to verify it fails**

Run: `npm test -- <frontend-upload-api-test-file>`
Expected: FAIL because the upload API helper does not exist yet.

**Step 3: Write minimal implementation**

Implement:

- upload response type in `src/services/api/types.ts`
- `uploadApi.uploadImage(file: File)` in `src/services/api/upload.ts`
- export wiring in `src/services/api/index.ts` if the codebase expects central exports

Keep the helper narrow: one endpoint, one file, one response shape.

**Step 4: Run test to verify it passes**

Run: `npm test -- <frontend-upload-api-test-file>`
Expected: PASS

**Step 5: Commit**

```bash
git add src/services/api/upload.ts src/services/api/types.ts src/services/api/index.ts
git commit -m "feat: add image upload api client"
```

### Task 2: Add editor image upload behavior with failing tests first

**Files:**
- Modify: `src/components/TiptapEditor.vue`
- Inspect: `src/utils/editorMarkdown.ts`
- Test: create or extend `src/components/TiptapEditor` test coverage

**Step 1: Write the failing test**

Add editor tests for:

- pasting a valid image triggers upload
- dropping a valid image triggers upload
- upload success inserts `![image](url)` into the editor output
- non-image paste leaves current behavior unchanged
- files over 10MB are rejected before upload

Mock the upload API rather than the editor internals.

**Step 2: Run test to verify it fails**

Run: `npm test -- <tiptap-editor-test-file>`
Expected: FAIL because paste/drop upload behavior is not implemented.

**Step 3: Write minimal implementation**

Implement in `src/components/TiptapEditor.vue`:

- paste event handling for clipboard image files
- drop event handling for dragged image files
- file type and size validation
- upload API call
- Markdown image insertion at the current selection or drop position

Do not add progress UI, retry logic, or a new toolbar button.

**Step 4: Run test to verify it passes**

Run: `npm test -- <tiptap-editor-test-file>`
Expected: PASS

**Step 5: Commit**

```bash
git add src/components/TiptapEditor.vue <tiptap-editor-test-file>
git commit -m "feat: support pasted and dropped task images"
```

### Task 3: Ensure Markdown conversion preserves images

**Files:**
- Modify: `src/utils/editorMarkdown.ts`
- Modify: `src/utils/editorMarkdown.test.ts`

**Step 1: Write the failing test**

Add tests proving:

- Markdown image syntax converts into editor HTML correctly
- editor HTML containing an image converts back to equivalent Markdown
- existing text formatting still round-trips after image support is added

**Step 2: Run test to verify it fails**

Run: `npm test -- src/utils/editorMarkdown.test.ts`
Expected: FAIL if the current conversion path drops or corrupts images.

**Step 3: Write minimal implementation**

Update the Markdown conversion utilities so image syntax is preserved across:

- initial editor content load
- editor updates back into Markdown

Prefer the smallest change that supports standard Markdown image syntax.

**Step 4: Run test to verify it passes**

Run: `npm test -- src/utils/editorMarkdown.test.ts`
Expected: PASS

**Step 5: Commit**

```bash
git add src/utils/editorMarkdown.ts src/utils/editorMarkdown.test.ts
git commit -m "feat: preserve markdown images in editor conversion"
```

### Task 4: Add backend upload endpoint contract

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/controller/UploadController.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/ImageUploadResponse.java`
- Inspect: `linear-lite-server/src/main/java/com/linearlite/server/common/ApiResponse.java`
- Test: create controller test or focused service test

**Step 1: Write the failing test**

Add a backend test that asserts `POST /api/uploads/images`:

- requires a multipart file
- returns `url` and `key` on success
- rejects invalid file types

**Step 2: Run test to verify it fails**

Run: `cd linear-lite-server && mvn test -Dtest=<UploadControllerTest>`
Expected: FAIL because the endpoint and DTO do not exist yet.

**Step 3: Write minimal implementation**

Implement:

- upload response DTO with `url` and `key`
- authenticated controller endpoint accepting `MultipartFile file`
- delegation into a storage-oriented service instead of embedding upload logic in the controller

**Step 4: Run test to verify it passes**

Run: `cd linear-lite-server && mvn test -Dtest=<UploadControllerTest>`
Expected: PASS

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/controller/UploadController.java linear-lite-server/src/main/java/com/linearlite/server/dto/ImageUploadResponse.java
git commit -m "feat: add image upload endpoint"
```

### Task 5: Add R2 storage service and configuration

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/config/R2StorageProperties.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/config/R2StorageConfig.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/ObjectStorageService.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/R2ObjectStorageService.java`
- Modify: `linear-lite-server/src/main/resources/application.yml`
- Modify: `linear-lite-server/pom.xml`
- Test: add backend tests for storage service behavior

**Step 1: Write the failing test**

Add service tests for:

- generating keys under `task-images/YYYY/MM/`
- preserving a safe extension
- rejecting files over 10MB
- rejecting unsupported MIME types
- building the public URL from `publicBaseUrl + key`

Mock the S3-compatible client so tests stay local and deterministic.

**Step 2: Run test to verify it fails**

Run: `cd linear-lite-server && mvn test -Dtest=<R2ObjectStorageServiceTest>`
Expected: FAIL because storage config and service do not exist yet.

**Step 3: Write minimal implementation**

Implement:

- typed configuration properties for endpoint, bucket, credentials, and public base URL
- S3-compatible client bean for Cloudflare R2
- storage service abstraction and R2 implementation
- MIME type, size, and file name sanitization
- upload logic returning `{ key, url }`

Only add the minimum SDK dependency needed for S3-compatible object upload.

**Step 4: Run test to verify it passes**

Run: `cd linear-lite-server && mvn test -Dtest=<R2ObjectStorageServiceTest>`
Expected: PASS

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/config linear-lite-server/src/main/java/com/linearlite/server/service linear-lite-server/src/main/resources/application.yml linear-lite-server/pom.xml
git commit -m "feat: add cloudflare r2 image storage service"
```

### Task 6: Wire backend endpoint to storage service and verify end-to-end server behavior

**Files:**
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/controller/UploadController.java`
- Modify: backend upload tests created earlier

**Step 1: Write the failing test**

Extend backend tests to assert:

- valid multipart uploads call the storage service once
- storage failures become clear API errors
- empty file uploads are rejected

**Step 2: Run test to verify it fails**

Run: `cd linear-lite-server && mvn test -Dtest=<UploadControllerTest>`
Expected: FAIL because the controller is not fully wired to the storage service and validation path yet.

**Step 3: Write minimal implementation**

Complete controller validation and exception handling integration so the endpoint returns stable responses for success and failure.

**Step 4: Run test to verify it passes**

Run: `cd linear-lite-server && mvn test -Dtest=<UploadControllerTest>`
Expected: PASS

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/controller/UploadController.java <backend-upload-test-files>
git commit -m "feat: complete upload endpoint validation"
```

### Task 7: Run integration verification for editor and task flows

**Files:**
- Inspect: `src/components/TaskEditor.vue`
- Inspect: `src/components/IssueComposer.vue`
- Inspect: `src/services/api/index.ts`

**Step 1: Write the failing test**

If coverage exists for task creation or editing flows, extend one test to prove the shared editor still works after image support. If not, add the smallest smoke test that mounts one of the host components with the shared editor and verifies description updates propagate.

**Step 2: Run test to verify it fails**

Run the selected frontend test command.
Expected: FAIL if host components are not handling the updated editor behavior correctly.

**Step 3: Write minimal implementation**

Make only the wiring changes required so both create and edit task flows continue to save Markdown descriptions with images.

**Step 4: Run test to verify it passes**

Run the same frontend test command.
Expected: PASS

**Step 5: Commit**

```bash
git add <host-component-files> <frontend-test-files>
git commit -m "feat: verify task flows with uploaded description images"
```

### Task 8: Final verification before completion

**Files:**
- No new files expected

**Step 1: Run frontend targeted tests**

Run:

```bash
npm test -- src/utils/editorMarkdown.test.ts <tiptap-editor-test-file> <frontend-upload-api-test-file>
```

Expected: PASS

**Step 2: Run backend targeted tests**

Run:

```bash
cd linear-lite-server && mvn test -Dtest=<UploadControllerTest>,<R2ObjectStorageServiceTest>
```

Expected: PASS

**Step 3: Run any existing broader smoke checks that are still cheap**

Run the smallest relevant additional checks already used by this repo for frontend and backend if they complete quickly.

**Step 4: Commit**

```bash
git status
```

Confirm only intended changes remain.
