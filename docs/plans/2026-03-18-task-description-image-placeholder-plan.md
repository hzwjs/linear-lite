# Task Description Image Placeholder Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make task description images render with first-frame placeholders so text and image positions are visible immediately, without waiting for remote image fetches.

**Architecture:** Convert Markdown image syntax into placeholder-aware HTML before Tiptap parses it, so existing task descriptions enter the editor as `taskImage` nodes with a remote-loading state from the first render. Then update the image node extension and editor load handling so remote images clear their placeholder in place after `load/error`, without relying on a later `setContent`.

**Tech Stack:** Vue 3, Tiptap, marked, Turndown, Vitest

---

### Task 1: Add a failing Markdown conversion test for remote image placeholders

**Files:**
- Modify: `src/utils/editorMarkdown.test.ts`

**Step 1: Write the failing test**

Add a test asserting `mdToHtml('before\n\n![image](https://cdn.example.com/demo.png)\n\nafter')` contains:
- `class="task-image-node`
- a remote-loading state attribute
- the original image URL

**Step 2: Run test to verify it fails**

Run: `npm test -- src/utils/editorMarkdown.test.ts`
Expected: FAIL because Markdown image conversion still produces plain `<img>` output without placeholder metadata.

**Step 3: Write minimal implementation**

Update `src/utils/editorMarkdown.ts` so Markdown image output is normalized into the placeholder-aware HTML shape expected by `taskImage`.

**Step 4: Run test to verify it passes**

Run: `npm test -- src/utils/editorMarkdown.test.ts`
Expected: PASS

### Task 2: Add a failing editor serialization test for remote-loading task images

**Files:**
- Modify: `src/utils/editorImageUpload.test.ts`
- Modify: `src/extensions/TaskImage.ts`

**Step 1: Write the failing test**

Add a test that initializes a Tiptap editor with placeholder HTML for a remote image and asserts:
- the node serializes back to the same Markdown image
- the rendered HTML keeps the placeholder wrapper before the load event clears it

**Step 2: Run test to verify it fails**

Run: `npm test -- src/utils/editorImageUpload.test.ts`
Expected: FAIL because `taskImage` does not yet distinguish remote-loading images from uploaded/uploading states.

**Step 3: Write minimal implementation**

Extend `src/extensions/TaskImage.ts` to support a remote-loading display state for existing images and preserve its placeholder wrapper until load/error.

**Step 4: Run test to verify it passes**

Run: `npm test -- src/utils/editorImageUpload.test.ts`
Expected: PASS

### Task 3: Make editor load handling clear remote placeholders in place

**Files:**
- Modify: `src/components/TiptapEditor.vue`
- Test: `src/utils/editorImageUpload.test.ts`

**Step 1: Write the failing test**

Add or extend a test that proves remote image placeholder markup is present before load and remains stable until the load handler clears the loading state in place.

**Step 2: Run test to verify it fails**

Run: `npm test -- src/utils/editorImageUpload.test.ts`
Expected: FAIL because editor load handling currently only strips a CSS class and has no remote-loading node state.

**Step 3: Write minimal implementation**

Update the delegated image `load/error` handling in `src/components/TiptapEditor.vue` to rewrite the node attributes in place for remote images, clearing placeholder state without reinitializing editor content.

**Step 4: Run test to verify it passes**

Run: `npm test -- src/utils/editorImageUpload.test.ts`
Expected: PASS

### Task 4: Run focused verification

**Files:**
- Verify only

**Step 1: Run targeted tests**

Run: `npm test -- src/utils/editorMarkdown.test.ts src/utils/editorImageUpload.test.ts`
Expected: PASS

**Step 2: Commit**

```bash
git add src/utils/editorMarkdown.ts src/utils/editorMarkdown.test.ts src/extensions/TaskImage.ts src/components/TiptapEditor.vue src/utils/editorImageUpload.test.ts docs/plans/2026-03-18-task-description-image-placeholder-plan.md
git commit -m "fix: render task description image placeholders immediately"
```
