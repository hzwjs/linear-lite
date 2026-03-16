# Project Invitations Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add email-based project invitations and restrict project/task visibility to project participants.

**Architecture:** Introduce explicit project membership and pending invitation tables on the backend, consume invitations during login/registration, and keep the frontend simple by continuing to drive sidebar state from `/api/projects`. Permission checks move server-side into project-aware service guards so task and project APIs only operate on participant-visible data.

**Tech Stack:** Spring Boot, MyBatis-Plus, Vue 3, Pinia, Axios, JUnit 5, Mockito, Vitest.

**Design reference:** `docs/plans/2026-03-16-project-invitations-design.md`

---

### Task 1: Backend membership and invitation schema

**Files:**
- Modify: `linear-lite-server/src/main/resources/schema.sql`
- Create: `linear-lite-server/src/main/resources/schema-v9-project-members.sql`
- Modify: `linear-lite-server/src/main/resources/data-init.sql`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/entity/ProjectMember.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/entity/ProjectInvitation.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/mapper/ProjectMemberMapper.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/mapper/ProjectInvitationMapper.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/CreateProjectInvitationRequest.java`

**Step 1: Write the failing backend tests**

Add tests that compile against membership and invitation entities plus project creation membership behavior.

**Step 2: Run tests to verify they fail**

Run: `cd linear-lite-server && mvn -q -Dtest=ProjectServiceTest,AuthServiceTest test`

Expected: FAIL because membership/invitation classes and schema-backed behavior do not exist yet.

**Step 3: Write minimal contract code**

- add entities, mappers, request DTO, schema, and seed membership rows

**Step 4: Run tests to verify compilation succeeds**

Run: `cd linear-lite-server && mvn -q -Dtest=ProjectServiceTest,AuthServiceTest test`

Expected: tests now fail on runtime assertions rather than missing classes.

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/resources/schema.sql linear-lite-server/src/main/resources/schema-v9-project-members.sql linear-lite-server/src/main/resources/data-init.sql linear-lite-server/src/main/java/com/linearlite/server/entity/ProjectMember.java linear-lite-server/src/main/java/com/linearlite/server/entity/ProjectInvitation.java linear-lite-server/src/main/java/com/linearlite/server/mapper/ProjectMemberMapper.java linear-lite-server/src/main/java/com/linearlite/server/mapper/ProjectInvitationMapper.java linear-lite-server/src/main/java/com/linearlite/server/dto/CreateProjectInvitationRequest.java
git commit -m "feat(projects): add membership schema"
```

### Task 2: Backend project visibility and invite behavior

**Files:**
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/controller/ProjectController.java`
- Modify: `linear-lite-server/src/test/java/com/linearlite/server/service/ProjectServiceTest.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/ProjectAccessService.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/ProjectInvitationService.java`

**Step 1: Write the failing tests**

Cover:

- creator is added to project members on create
- project list returns only memberships for current user
- owner can invite unknown email and pending invite is stored
- owner inviting existing user adds membership immediately
- non-owner/non-member invite attempt is rejected

**Step 2: Run tests to verify they fail**

Run: `cd linear-lite-server && mvn -q -Dtest=ProjectServiceTest test`

Expected: FAIL with unmet behavior assertions.

**Step 3: Write minimal implementation**

- make `list` user-aware
- add membership insertion on create
- add invite API/service
- enforce owner/member checks

**Step 4: Run tests to verify they pass**

Run: `cd linear-lite-server && mvn -q -Dtest=ProjectServiceTest test`

Expected: PASS

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java linear-lite-server/src/main/java/com/linearlite/server/controller/ProjectController.java linear-lite-server/src/test/java/com/linearlite/server/service/ProjectServiceTest.java linear-lite-server/src/main/java/com/linearlite/server/service/ProjectAccessService.java linear-lite-server/src/main/java/com/linearlite/server/service/ProjectInvitationService.java
git commit -m "feat(projects): add invite and membership checks"
```

### Task 3: Consume invitations during auth and task access checks

**Files:**
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/service/AuthService.java`
- Modify: `linear-lite-server/src/test/java/com/linearlite/server/service/AuthServiceTest.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java`

**Step 1: Write the failing tests**

Cover:

- login accepts pending project invitations
- register accepts pending project invitations
- task list/create/import reject users who are not project members

**Step 2: Run tests to verify they fail**

Run: `cd linear-lite-server && mvn -q -Dtest=AuthServiceTest,TaskServiceTest test`

Expected: FAIL

**Step 3: Write minimal implementation**

- invoke invitation acceptance after successful auth
- enforce membership checks before task operations

**Step 4: Run tests to verify they pass**

Run: `cd linear-lite-server && mvn -q -Dtest=AuthServiceTest,TaskServiceTest test`

Expected: PASS

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/service/AuthService.java linear-lite-server/src/test/java/com/linearlite/server/service/AuthServiceTest.java linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java
git commit -m "feat(auth): accept project invitations on auth"
```

### Task 4: Frontend invitation API and UI

**Files:**
- Modify: `src/services/api/project.ts`
- Modify: `src/services/api/types.ts`
- Modify: `src/store/projectStore.ts`
- Modify: `src/components/ProjectSettingsModal.vue`
- Modify: `src/store/projectStore.test.ts`
- Create: `tests/projectApi.test.ts` if needed

**Step 1: Write the failing frontend tests**

Cover:

- invite API posts email to the correct route
- project store still preserves/repicks active project with filtered results
- project settings invite action calls store and shows errors

**Step 2: Run tests to verify they fail**

Run: `npm test -- tests/projectApi.test.ts src/store/projectStore.test.ts`

Expected: FAIL

**Step 3: Write minimal implementation**

- add invite API/store action
- add invite form to project settings modal

**Step 4: Run tests to verify they pass**

Run: `npm test -- tests/projectApi.test.ts src/store/projectStore.test.ts`

Expected: PASS

**Step 5: Commit**

```bash
git add src/services/api/project.ts src/services/api/types.ts src/store/projectStore.ts src/components/ProjectSettingsModal.vue src/store/projectStore.test.ts tests/projectApi.test.ts
git commit -m "feat(ui): add project invite flow"
```

### Task 5: Full verification

**Files:**
- No additional files expected unless verification finds gaps

**Step 1: Run backend tests**

Run: `cd linear-lite-server && mvn -q test`

Expected: PASS

**Step 2: Run frontend tests**

Run: `npm test`

Expected: PASS

**Step 3: Run frontend build**

Run: `npm run build`

Expected: PASS

**Step 4: Manual smoke-check**

- create a project as user A
- invite an unregistered email
- register as that email
- verify user B sees only invited projects
- verify unrelated user sees none of those projects
- verify non-member task/project access is blocked
