# Task Import Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a first-version task import flow for the active project that supports `.csv` / `.xlsx`, basic task fields, parent-child relationships, and a hard limit of 800 rows.

**Architecture:** The frontend parses files, maps columns, validates rows, and submits a structured import payload. The backend owns final validation and a transactional two-phase create flow so parent-child relationships are applied consistently without partial imports.

**Tech Stack:** Vue 3, TypeScript, Pinia, Vitest, Spring Boot 3, MyBatis-Plus, JUnit 5, Mockito

---

### Task 1: Add frontend import parsing utilities

**Files:**
- Create: `src/utils/taskImport.ts`
- Create: `src/utils/taskImport.test.ts`
- Check: `package.json`

**Step 1: Write the failing tests**

Add tests for:

- auto-mapping known column aliases to target fields
- rejecting files with more than 800 rows
- validating missing `title`
- validating duplicate `importId`
- validating invalid `status`, `priority`, and `dueDate`
- validating missing `parentImportId` targets

**Step 2: Run test to verify it fails**

Run: `npm test -- src/utils/taskImport.test.ts`
Expected: FAIL because `src/utils/taskImport.ts` does not exist yet.

**Step 3: Write minimal implementation**

Implement utility functions for:

- supported extension checks
- normalized import row shape
- alias-based column mapping
- row validation and preview summary generation

Prefer pure functions so the modal can stay thin.

**Step 4: Run test to verify it passes**

Run: `npm test -- src/utils/taskImport.test.ts`
Expected: PASS

**Step 5: Commit**

```bash
git add src/utils/taskImport.ts src/utils/taskImport.test.ts package.json
git commit -m "feat: add task import parsing utilities"
```

### Task 2: Add frontend API types and import request client

**Files:**
- Modify: `src/services/api/types.ts`
- Modify: `src/services/api/task.ts`

**Step 1: Write the failing test**

If this codebase already tests API helpers directly, add or extend a test for the import request shape. If not, add a focused test around the transformation helper used before `taskApi.import`.

**Step 2: Run test to verify it fails**

Run the relevant frontend test command for the added test.
Expected: FAIL because import API types and method are missing.

**Step 3: Write minimal implementation**

Add:

- import row request type
- import request / response DTOs
- `taskApi.import(projectId, rows)` method

Keep the method aligned with the future backend endpoint `POST /api/tasks/import`.

**Step 4: Run test to verify it passes**

Run the same test command.
Expected: PASS

**Step 5: Commit**

```bash
git add src/services/api/types.ts src/services/api/task.ts
git commit -m "feat: add task import api client"
```

### Task 3: Add import modal UI and preview workflow

**Files:**
- Create: `src/components/TaskImportModal.vue`
- Modify: `src/views/BoardView.vue`
- Inspect: `src/components/IssueComposer.vue`
- Inspect: `src/store/overlayStore.ts`
- Inspect: `src/store/issuePanelStore.ts`

**Step 1: Write the failing test**

Add a component test for:

- opening the import modal from the board toolbar
- blocking progress when required mappings are missing
- showing preview summary and validation errors

If component test infra is weak, write a utility-driven test for modal state transitions and keep the UI smoke coverage lightweight.

**Step 2: Run test to verify it fails**

Run the selected frontend test command.
Expected: FAIL because the modal and trigger do not exist.

**Step 3: Write minimal implementation**

Implement:

- import button in the board toolbar
- modal with four states: upload, mapping, preview, result
- current-project binding
- success callback that triggers `store.fetchTasks()`

Reuse existing overlay and modal styling patterns from `IssueComposer.vue` where reasonable.

**Step 4: Run test to verify it passes**

Run the same frontend test command.
Expected: PASS

**Step 5: Commit**

```bash
git add src/components/TaskImportModal.vue src/views/BoardView.vue
git commit -m "feat: add task import modal workflow"
```

### Task 4: Add backend DTOs and controller endpoint for imports

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/TaskImportRowRequest.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/TaskImportRequest.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/TaskImportResponse.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/TaskImportErrorResponse.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java`

**Step 1: Write the failing test**

Add a controller or service-level test that expects `POST /api/tasks/import` to delegate to a task import service method and return a structured success payload.

**Step 2: Run test to verify it fails**

Run: `cd linear-lite-server && mvn test -Dtest=TaskControllerTest`
Expected: FAIL because DTOs and endpoint do not exist yet.

**Step 3: Write minimal implementation**

Add request / response DTOs and controller endpoint. Keep the controller thin and forward validated data to `TaskService`.

**Step 4: Run test to verify it passes**

Run: `cd linear-lite-server && mvn test -Dtest=TaskControllerTest`
Expected: PASS

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/dto linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java
git commit -m "feat: add task import endpoint"
```

### Task 5: Implement transactional backend import service

**Files:**
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java`
- Inspect: `linear-lite-server/src/main/java/com/linearlite/server/entity/Task.java`
- Inspect: `linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskMapper.java`

**Step 1: Write the failing test**

Add `TaskServiceTest` cases for:

- creating multiple tasks in one import
- wiring parent-child relationships via `importId` / `parentImportId`
- rejecting invalid status / priority
- rejecting missing parent references
- rolling back on any failure

**Step 2: Run test to verify it fails**

Run: `cd linear-lite-server && mvn test -Dtest=TaskServiceTest`
Expected: FAIL because bulk import behavior is not implemented.

**Step 3: Write minimal implementation**

Implement `TaskService.importTasks(...)` using:

- project validation
- row count limit enforcement at 800
- import row validation
- phase 1 task creation without parent IDs
- `importId -> task.id` mapping
- phase 2 parent assignment
- summary response generation

Make the method transactional so any failure rolls back the full batch.

**Step 4: Run test to verify it passes**

Run: `cd linear-lite-server && mvn test -Dtest=TaskServiceTest`
Expected: PASS

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java linear-lite-server/src/test/java/com/linearlite/server/service/TaskServiceTest.java
git commit -m "feat: add transactional task import service"
```

### Task 6: Wire frontend submit flow to backend and handle result state

**Files:**
- Modify: `src/components/TaskImportModal.vue`
- Modify: `src/services/api/task.ts`
- Modify: `src/store/taskStore.ts`

**Step 1: Write the failing test**

Add coverage for:

- submit success transitions to result state
- submit failure renders backend errors
- successful import refreshes the active project task list

**Step 2: Run test to verify it fails**

Run the relevant frontend test command.
Expected: FAIL because modal submit is not yet wired.

**Step 3: Write minimal implementation**

Submit the structured payload to the backend import endpoint, show loading state, render result summary, and refresh tasks on success.

**Step 4: Run test to verify it passes**

Run the same frontend test command.
Expected: PASS

**Step 5: Commit**

```bash
git add src/components/TaskImportModal.vue src/services/api/task.ts src/store/taskStore.ts
git commit -m "feat: connect task import submit flow"
```

### Task 7: Add docs and manual verification notes

**Files:**
- Modify: `README.md`
- Optionally Modify: `linear-lite-server/README.md`
- Create: `docs/plans/2026-03-16-task-import-manual-checklist.md`

**Step 1: Write the failing test**

No automated test required for docs-only work.

**Step 2: Run a verification command to capture current behavior**

Run the chosen automated test suites before documenting final verification steps.

**Step 3: Write minimal implementation**

Document:

- supported file formats
- required template columns
- 800-row limit
- import behavior: create-only and all-or-nothing
- manual verification checklist

**Step 4: Run verification to confirm docs reflect reality**

Run:

- `npm test`
- `cd linear-lite-server && mvn test`

Expected: PASS

**Step 5: Commit**

```bash
git add README.md linear-lite-server/README.md docs/plans/2026-03-16-task-import-manual-checklist.md
git commit -m "docs: add task import usage and verification notes"
```

### Task 8: Final verification

**Files:**
- No code changes expected

**Step 1: Run frontend tests**

Run: `npm test`
Expected: PASS

**Step 2: Run backend tests**

Run: `cd linear-lite-server && mvn test`
Expected: PASS

**Step 3: Do manual smoke verification**

Run the app and verify:

- valid CSV import succeeds
- valid XLSX import succeeds
- parent-child relationships render correctly
- invalid assignee or invalid status is blocked before submit or rejected by backend
- over-800-row file is blocked

**Step 4: Commit if any verification-related fixes were needed**

```bash
git add <any changed files>
git commit -m "test: fix verification issues for task import"
```
