# R2 Image Upload UX Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make pasted and dropped task-description images appear immediately as local previews, upload asynchronously to Cloudflare R2 with per-image status bars, and preserve failed previews with retry/remove actions.

**Architecture:** The editor will insert runtime image nodes backed by local `blob:` preview URLs and metadata describing upload state. A custom image NodeView will render the preview plus status bar. Upload tasks run independently per image and update the matching node on success or failure. Save-time serialization will only allow successfully uploaded images into Markdown and block persistence while any image remains uploading or failed.

**Tech Stack:** Vue 3, TypeScript, Tiptap, custom NodeView, Vitest, Spring Boot 3 backend upload endpoint already in place

---

### Task 1: Add failing tests for runtime image upload states

**Files:**
- Modify: `src/utils/editorImageUpload.test.ts`
- Create or modify: focused tests around editor image runtime behavior

**Step 1: Write the failing test**

Add tests that prove:

- inserting a preview image plus placeholder status is preserved in editor state
- appending a paragraph after an image does not erase the image
- failed images can remain in runtime state without being serialized into final Markdown

**Step 2: Run test to verify it fails**

Run: `npm test -- src/utils/editorImageUpload.test.ts`
Expected: FAIL because current editor/runtime model does not distinguish upload states.

**Step 3: Write minimal implementation**

Add the minimum runtime helpers needed for:

- local image node metadata
- upload state transitions
- serialization filtering rules

**Step 4: Run test to verify it passes**

Run: `npm test -- src/utils/editorImageUpload.test.ts`
Expected: PASS

**Step 5: Commit**

```bash
git add src/utils/editorImageUpload.test.ts src/utils/editorImageUpload.ts
git commit -m "test: cover image upload runtime states"
```

### Task 2: Add custom Tiptap image node and NodeView for status bars

**Files:**
- Create: `src/extensions/TaskImage.ts`
- Create: `src/components/TaskImageNodeView.vue`
- Modify: `src/components/TiptapEditor.vue`
- Inspect: existing custom node pattern in `src/extensions/CodeBlockLinear.ts`
- Inspect: `src/components/CodeBlockLinearView.vue`

**Step 1: Write the failing test**

Add tests proving:

- image nodes render with status bar when `uploadState=uploading`
- failed nodes render `Retry` and `Remove`
- uploaded nodes render without status bar

If direct component mounting is simpler, test the NodeView component behavior separately from the editor shell.

**Step 2: Run test to verify it fails**

Run the targeted frontend test command for the new NodeView tests.
Expected: FAIL because the custom image node and NodeView do not exist.

**Step 3: Write minimal implementation**

Implement:

- custom image node extension with attrs: `src`, `alt`, `localId`, `uploadState`, `errorMessage`
- NodeView for preview + status bar UI
- registration of that extension in `TiptapEditor`

**Step 4: Run test to verify it passes**

Run the same targeted test command.
Expected: PASS

**Step 5: Commit**

```bash
git add src/extensions/TaskImage.ts src/components/TaskImageNodeView.vue src/components/TiptapEditor.vue
git commit -m "feat: add image node view with upload status bar"
```

### Task 3: Insert local previews immediately and upload each image independently

**Files:**
- Modify: `src/components/TiptapEditor.vue`
- Modify: `src/utils/editorImageUpload.ts`
- Modify: `src/services/api/upload.ts`

**Step 1: Write the failing test**

Add tests for:

- paste of multiple images inserts multiple local previews immediately
- each image starts in `uploading`
- success updates only the matching image node
- failure updates only the matching image node

Mock the upload API with mixed success/failure outcomes.

**Step 2: Run test to verify it fails**

Run the targeted test command.
Expected: FAIL because current implementation waits for upload before rendering.

**Step 3: Write minimal implementation**

Implement:

- `blob:` preview generation
- per-image `localId`
- independent async upload jobs
- node attr updates for success/failure
- `URL.revokeObjectURL` when local previews are removed or replaced

**Step 4: Run test to verify it passes**

Run the same targeted test command.
Expected: PASS

**Step 5: Commit**

```bash
git add src/components/TiptapEditor.vue src/utils/editorImageUpload.ts src/services/api/upload.ts
git commit -m "feat: add immediate local previews for image uploads"
```

### Task 4: Add retry and remove actions for failed images

**Files:**
- Modify: `src/components/TaskImageNodeView.vue`
- Modify: `src/components/TiptapEditor.vue`
- Test: NodeView / editor interaction tests

**Step 1: Write the failing test**

Add tests proving:

- clicking `Retry` restarts upload for the failed node
- clicking `Remove` deletes only that node
- retry success clears the failed state

**Step 2: Run test to verify it fails**

Run the targeted frontend test command.
Expected: FAIL because retry/remove actions are not wired.

**Step 3: Write minimal implementation**

Wire node-level actions so the editor can:

- retry using the original `File` data tracked by `localId`
- remove the node and revoke the local preview URL

**Step 4: Run test to verify it passes**

Run the same targeted frontend test command.
Expected: PASS

**Step 5: Commit**

```bash
git add src/components/TaskImageNodeView.vue src/components/TiptapEditor.vue
git commit -m "feat: add retry and remove actions for failed image uploads"
```

### Task 5: Block persistence when unresolved images remain

**Files:**
- Modify: `src/components/TiptapEditor.vue`
- Modify: `src/utils/editorMarkdown.ts` or serialization helper layer
- Modify: `src/components/TaskEditor.vue`
- Modify: `src/components/IssueComposer.vue`
- Test: save-path tests or focused serialization tests

**Step 1: Write the failing test**

Add tests proving:

- uploaded images serialize to Markdown normally
- `uploading` images do not serialize into final Markdown
- `failed` images do not serialize into final Markdown
- save/create flows are blocked or guarded while unresolved images remain

**Step 2: Run test to verify it fails**

Run the selected frontend test command.
Expected: FAIL because current save path has no unresolved-image guard.

**Step 3: Write minimal implementation**

Implement:

- runtime-only filtering for non-uploaded images
- a guard exposed from `TiptapEditor` so host forms know unresolved images still exist
- host-component behavior for save blocking and user-visible error

**Step 4: Run test to verify it passes**

Run the same test command.
Expected: PASS

**Step 5: Commit**

```bash
git add src/components/TiptapEditor.vue src/components/TaskEditor.vue src/components/IssueComposer.vue src/utils/editorMarkdown.ts
git commit -m "feat: block task saves with unresolved image uploads"
```

### Task 6: Final verification

**Files:**
- No new files expected

**Step 1: Run targeted frontend tests**

Run:

```bash
npm test -- src/utils/editorImageUpload.test.ts src/utils/editorMarkdown.test.ts tests/uploadApi.test.ts <nodeview-test-files>
```

Expected: PASS

**Step 2: Run frontend build**

Run:

```bash
npm run build
```

Expected: PASS

**Step 3: Run backend upload tests**

Run:

```bash
cd linear-lite-server && mvn test -Dtest=UploadControllerTest,R2ObjectStorageServiceTest
```

Expected: PASS

**Step 4: Manual verification**

Verify in the browser:

- single-image paste shows immediate preview + status bar
- multi-image paste shows independent status bars
- slow network still shows local preview immediately
- failed image shows retry/remove
- successful image persists to task description and database

**Step 5: Commit**

```bash
git status
```

Confirm only intended changes remain.
