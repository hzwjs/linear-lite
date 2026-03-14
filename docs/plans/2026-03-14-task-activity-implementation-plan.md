# Task Activity Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Persist task activity events and render them in the task detail Activity section so task modifications appear in history.

**Architecture:** Add a backend `task_activities` event log with a dedicated mapper/service, wire task mutations to record structured events, then expose a read API consumed by the task detail UI. Frontend formatting stays thin: it renders backend activity records with small field-specific display helpers.

**Tech Stack:** Vue 3, Pinia, TypeScript, Vitest, Spring Boot 3, MyBatis-Plus, JUnit 5, Mockito, MySQL schema migrations

---

### Task 1: Backend activity recording

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/entity/TaskActivity.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskActivityMapper.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/TaskActivityResponse.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/TaskActivityService.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java`
- Modify: `linear-lite-server/src/main/resources/schema.sql`
- Create: `linear-lite-server/src/main/resources/schema-v6-task-activities.sql`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/TaskServiceTest.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/TaskActivityServiceTest.java`

**Step 1: Write the failing tests**

- Add tests proving create/update/favorite flows write activity records
- Add a test proving no-op updates do not write records
- Add a test proving activity list resolves actor name and event fields

**Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=TaskServiceTest,TaskActivityServiceTest test`

**Step 3: Write minimal implementation**

- Add activity table model and mapper
- Add service methods to record and list events
- Call those methods from task create/update/favorite paths
- Add `GET /api/tasks/{taskKey}/activities`

**Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=TaskServiceTest,TaskActivityServiceTest test`

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/entity/TaskActivity.java linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskActivityMapper.java linear-lite-server/src/main/java/com/linearlite/server/dto/TaskActivityResponse.java linear-lite-server/src/main/java/com/linearlite/server/service/TaskActivityService.java linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java linear-lite-server/src/main/resources/schema.sql linear-lite-server/src/main/resources/schema-v6-task-activities.sql linear-lite-server/src/test/java/com/linearlite/server/service/TaskServiceTest.java linear-lite-server/src/test/java/com/linearlite/server/service/TaskActivityServiceTest.java
git commit -m "feat: persist task activity events"
```

### Task 2: Frontend activity loading and rendering

**Files:**
- Create: `src/services/api/activity.ts`
- Create: `src/utils/taskActivity.ts`
- Create: `src/utils/taskActivity.test.ts`
- Modify: `src/services/api/types.ts`
- Test: `tests/taskApi.test.ts`
- Modify: `src/components/TaskEditor.vue`

**Step 1: Write the failing tests**

- Add API mapping tests for task activity payloads
- Add formatting tests for activity copy

**Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/taskApi.test.ts src/utils/taskActivity.test.ts`

**Step 3: Write minimal implementation**

- Add activity API client and typed payload mapping
- Replace static Activity line in task detail with fetched activity list
- Use a helper to map field names and values to display text

**Step 4: Run tests to verify they pass**

Run: `npx vitest run tests/taskApi.test.ts src/utils/taskActivity.test.ts`

**Step 5: Commit**

```bash
git add src/services/api/activity.ts src/services/api/types.ts src/utils/taskActivity.ts src/utils/taskActivity.test.ts src/components/TaskEditor.vue tests/taskApi.test.ts
git commit -m "feat: render task activity history"
```

### Task 3: Full verification

**Files:**
- Modify: none unless verification reveals gaps

**Step 1: Run targeted frontend tests**

Run: `npx vitest run tests/taskApi.test.ts src/store/favoriteStore.test.ts src/store/taskStore.test.ts src/utils/taskActivity.test.ts`

**Step 2: Run backend tests**

Run: `mvn -q test`

**Step 3: Run production build**

Run: `npm run build`

**Step 4: Confirm manual behavior**

- Edit a task title, status, priority, assignee, due date
- Reopen the task detail
- Confirm each change appears in Activity with actor + timestamp

**Step 5: Commit if verification required edits**

```bash
git add -A
git commit -m "test: verify task activity flow"
```
