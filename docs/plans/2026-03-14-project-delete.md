# Project Delete Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add project deletion with cascading removal of project tasks, favorites, and activities, and restrict deletion to the project creator.

**Architecture:** Extend the backend project model with `creator_id`, enforce deletion authorization in `ProjectService`, and expose a delete API from `ProjectController`. On the frontend, surface the delete action in project settings, wire it through `projectStore`, and refresh active project/task state after deletion.

**Tech Stack:** Vue 3, Pinia, Vitest, Spring Boot, MyBatis-Plus, JUnit 5, Mockito

---

### Task 1: Add failing backend tests for project deletion rules

**Files:**
- Modify: `linear-lite-server/src/test/java/com/linearlite/server/service/ProjectServiceTest.java`

**Step 1: Write the failing test**

Add tests for:
- deleting a project owned by the current user removes its tasks, favorites, activities, and the project
- deleting a project owned by another user throws a permission error

**Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ProjectServiceTest test`
Expected: FAIL because delete behavior and/or permission handling does not exist yet

**Step 3: Write minimal implementation**

Implement only enough service logic and dependencies to satisfy the failing tests.

**Step 4: Run test to verify it passes**

Run: `mvn -Dtest=ProjectServiceTest test`
Expected: PASS

### Task 2: Add failing backend tests for controller delete endpoint

**Files:**
- Modify: `linear-lite-server/src/test/java/com/linearlite/server/controller/ProjectControllerTest.java` or create if absent

**Step 1: Write the failing test**

Add a test showing `DELETE /api/projects/{id}` reads the current user from request context and forwards it to the service.

**Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ProjectControllerTest test`
Expected: FAIL because the endpoint does not exist yet

**Step 3: Write minimal implementation**

Add the controller delete endpoint and request-user extraction.

**Step 4: Run test to verify it passes**

Run: `mvn -Dtest=ProjectControllerTest test`
Expected: PASS

### Task 3: Add failing frontend store tests for active-project behavior after delete

**Files:**
- Modify: `src/store/projectStore.test.ts` or create if absent

**Step 1: Write the failing test**

Add tests for:
- deleting the active project switches to the first remaining project
- deleting the last project clears `activeProjectId`

**Step 2: Run test to verify it fails**

Run: `npm test -- src/store/projectStore.test.ts`
Expected: FAIL because delete logic does not exist yet

**Step 3: Write minimal implementation**

Add `projectApi.delete()` and `projectStore.deleteProject()`.

**Step 4: Run test to verify it passes**

Run: `npm test -- src/store/projectStore.test.ts`
Expected: PASS

### Task 4: Add failing frontend UI test or implement minimally verified delete affordance

**Files:**
- Modify: `src/components/ProjectSettingsModal.vue`

**Step 1: Write the failing test**

If component tests are already in use, add a test showing the delete control is shown only for the creator and triggers store deletion on confirmation. If component tests are not present, skip to implementation and verify behavior through store coverage plus manual runtime verification.

**Step 2: Run test to verify it fails**

Run the relevant component test if added.

**Step 3: Write minimal implementation**

Add a danger section, confirmation flow, disabled submitting state, and error handling.

**Step 4: Run test to verify it passes**

Run the relevant test if added, otherwise verify via app behavior and document that manual verification was used.

### Task 5: Add schema/model changes and end-to-end verification

**Files:**
- Modify: `linear-lite-server/src/main/resources/schema.sql`
- Modify: `linear-lite-server/src/main/resources/data-init.sql`
- Create: `linear-lite-server/src/main/resources/schema-v7-project-creator.sql`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/entity/Project.java`
- Modify: `src/types/domain.ts`
- Modify: `src/services/api/project.ts`

**Step 1: Write the failing test**

Use the existing backend/frontend tests to reveal missing `creatorId` shape or data setup.

**Step 2: Run test to verify it fails**

Run:
- `mvn test`
- `npm test -- src/store/projectStore.test.ts src/store/taskStore.test.ts`

Expected: FAIL until schema/model/API shape is consistent

**Step 3: Write minimal implementation**

Add the field through schema, model, API mapping, and seed data.

**Step 4: Run test to verify it passes**

Run:
- `mvn test`
- `npm test -- src/store/projectStore.test.ts src/store/taskStore.test.ts`

Expected: PASS
