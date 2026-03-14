# Subtask Progress Pill Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the task list subtask counter visually match Linear more closely with a ring-plus-count pill while keeping current display scope and semantics.

**Architecture:** Extract the subtask progress display state into a small pure utility so the visual component can stay simple and the behavior can be test-driven without adding new Vue test infrastructure. Update the task list row markup to render a compact SVG progress ring with a low-emphasis count pill and completed-state styling.

**Tech Stack:** Vue 3, TypeScript, Vitest, scoped CSS

---

### Task 1: Define display-state contract

**Files:**
- Create: `src/utils/subtaskProgress.ts`
- Test: `src/utils/subtaskProgress.test.ts`

**Step 1: Write the failing test**

Cover:
- no pill when total is 0
- ratio clamps between 0 and 1
- completed state when done equals total
- count text stays `done/total`

**Step 2: Run test to verify it fails**

Run: `npx vitest run src/utils/subtaskProgress.test.ts`
Expected: FAIL because the module does not exist yet.

**Step 3: Write minimal implementation**

Return a normalized object with `visible`, `countText`, `progress`, and `completed`.

**Step 4: Run test to verify it passes**

Run: `npx vitest run src/utils/subtaskProgress.test.ts`
Expected: PASS

### Task 2: Render the Linear-like pill in the task list

**Files:**
- Modify: `src/components/TaskListView.vue`

**Step 1: Replace inline count-string helper**

Use the new utility per row instead of returning plain text.

**Step 2: Add compact SVG ring + count pill markup**

Render only when `visible` is true.

**Step 3: Update styles**

Add low-emphasis pill styles, ring track/progress styles, and completed-state colors while keeping title as the primary visual element.

### Task 3: Verify

**Files:**
- Modify: none

**Step 1: Run focused tests**

Run: `npx vitest run src/utils/subtaskProgress.test.ts`

**Step 2: Run type/build verification**

Run: `npm run build`

