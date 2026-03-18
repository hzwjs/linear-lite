# Task Description Image UX Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Improve task description image UX with better loading skeletons, responsive image sizing, and a full gallery viewer.

**Architecture:** Keep saved task description images on the native Tiptap image path so existing layout semantics remain intact. Add view-layer enhancements only: attach a Vue 3 skeleton treatment while remote images are still loading, constrain rendered images to the editor content width, and open clicked description images in a PhotoSwipe gallery using the images already present in the current description.

**Tech Stack:** Vue 3, Tiptap, Vitest, PhotoSwipe, vue3-skeleton

---

### Task 1: Add failing tests for native remote image rendering expectations

**Files:**
- Modify: `src/utils/editorMarkdown.test.ts`
- Modify: `src/utils/editorImageUpload.test.ts`

**Step 1: Write the failing tests**

Add tests that assert:
- saved Markdown images still render as native `<img>` elements
- loading styles target native remote images without wrapping them in custom task-image nodes

**Step 2: Run test to verify it fails**

Run: `npm test -- src/utils/editorMarkdown.test.ts src/utils/editorImageUpload.test.ts`
Expected: FAIL if current implementation still depends on structural wrappers for saved images.

**Step 3: Write minimal implementation**

Adjust editor rendering code so saved images remain native and loading treatment is purely visual.

**Step 4: Run test to verify it passes**

Run: `npm test -- src/utils/editorMarkdown.test.ts src/utils/editorImageUpload.test.ts`
Expected: PASS

### Task 2: Add skeleton dependency and integrate loading visuals

**Files:**
- Modify: `package.json`
- Modify: `src/components/TiptapEditor.vue`

**Step 1: Install dependency**

Run: `npm install @brayamvalero/vue3-skeleton`

**Step 2: Add failing test or fixture assertion**

Add the smallest assertion that the editor references the skeleton-driven loading class or component path.

**Step 3: Write minimal implementation**

Replace the plain gray remote-image loading treatment with a skeleton-backed visual that preserves existing image layout.

**Step 4: Run verification**

Run: `npm test -- src/utils/editorMarkdown.test.ts src/utils/editorImageUpload.test.ts`
Expected: PASS

### Task 3: Add responsive sizing rules for description images

**Files:**
- Modify: `src/components/TiptapEditor.vue`

**Step 1: Write failing check**

Add or extend a test/assertion to cover max-width constrained image rendering inside the editor.

**Step 2: Write minimal implementation**

Ensure description images scale to the current editor content width while preserving intrinsic aspect ratio.

**Step 3: Verify**

Run: `npm test -- src/utils/editorMarkdown.test.ts src/utils/editorImageUpload.test.ts`
Expected: PASS

### Task 4: Add PhotoSwipe gallery viewer for description images

**Files:**
- Modify: `package.json`
- Modify: `src/components/TiptapEditor.vue`
- Optionally create: `src/components/DescriptionImageGallery.vue`

**Step 1: Install dependency**

Run: `npm install photoswipe`

**Step 2: Write failing test or fixture assertion**

Add the smallest test/assertion that clicking a saved description image enters the gallery code path.

**Step 3: Write minimal implementation**

Collect the current description’s saved image elements, build a PhotoSwipe data source, and open the viewer at the clicked image index.

**Step 4: Verify**

Run: `npm test -- src/utils/editorMarkdown.test.ts src/utils/editorImageUpload.test.ts`
Expected: PASS

### Task 5: Run focused verification

**Files:**
- Verify only

**Step 1: Run targeted tests**

Run: `npm test -- src/utils/editorMarkdown.test.ts src/utils/editorImageUpload.test.ts`
Expected: PASS

**Step 2: Manual verification**

Verify in the running app that:
- remote images show skeletons while loading
- images scale with editor width
- clicking an image opens a full gallery with next/previous navigation and zoom

