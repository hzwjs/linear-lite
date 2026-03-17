# Checklist Editor Fix Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix checklist rendering in the task description editor so the checkbox has no extra bullet marker and the text stays on the same row as the checkbox.

**Architecture:** Keep the fix local to the Tiptap editor. Lock the expected checklist DOM/styling contract with a source-level test first, then apply the smallest Tiptap configuration and scoped CSS changes needed for task list rendering.

**Tech Stack:** Vue 3, Tiptap, Vitest

---

### Task 1: Lock the checklist rendering contract

**Files:**
- Modify: `src/components/TaskTypography.test.ts`
- Test: `src/components/TaskTypography.test.ts`

**Step 1: Write the failing test**

Add assertions that require checklist items in `TiptapEditor.vue` to:
- configure `TaskItem` with nested support
- remove default list markers
- render checkbox and content in one row with flex layout

**Step 2: Run test to verify it fails**

Run: `npm test -- src/components/TaskTypography.test.ts`
Expected: FAIL because the new checklist assertions are not present in `TiptapEditor.vue`

### Task 2: Apply the minimal checklist fix

**Files:**
- Modify: `src/components/TiptapEditor.vue`
- Test: `src/components/TaskTypography.test.ts`

**Step 1: Write minimal implementation**

Update the editor to:
- configure `TaskItem` with `nested: true`
- add scoped checklist styles that remove the list marker and keep the checkbox/content aligned inline

**Step 2: Run tests to verify it passes**

Run: `npm test -- src/components/TaskTypography.test.ts`
Expected: PASS
