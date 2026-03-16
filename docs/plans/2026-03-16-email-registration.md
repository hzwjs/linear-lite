# Email Registration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add email verification-based registration and support signing in with either username or email.

**Architecture:** Extend the backend auth flow with a dedicated verification-code table and SMTP mail sender, then update the frontend auth screen to support login and registration modes against the new API. Keep the existing JWT session model unchanged so registration and login both land in the same auth store path.

**Tech Stack:** Spring Boot, MyBatis-Plus, JavaMailSender, Vue 3, Pinia, Axios, Vitest, JUnit 5, Mockito.

**Design reference:** `docs/plans/2026-03-16-email-registration-design.md`

---

### Task 1: Backend auth contract and schema

**Files:**
- Modify: `linear-lite-server/src/main/resources/schema.sql`
- Modify: `linear-lite-server/src/main/resources/application.yml`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/SendRegisterCodeRequest.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/RegisterRequest.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/dto/LoginRequest.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/entity/EmailVerificationCode.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/mapper/EmailVerificationCodeMapper.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/entity/User.java`

**Step 1: Write the failing backend tests**

Add tests that compile against the new DTO contract and expect `users.email` plus verification-code storage to exist.

**Step 2: Run tests to verify they fail**

Run: `cd linear-lite-server && ./mvnw -q -Dtest=AuthControllerTest test`

Expected: FAIL because the DTOs, entity, mapper, and schema-backed behavior do not exist yet.

**Step 3: Write minimal contract code**

- change login request from `username` to `identity`
- add register and send-code DTOs
- add `email` to `User`
- add verification-code entity and mapper
- add schema entries and mail config placeholders

**Step 4: Run tests to verify compilation passes and behavior tests still fail**

Run: `cd linear-lite-server && ./mvnw -q -Dtest=AuthControllerTest test`

Expected: FAIL only on runtime behavior assertions, not missing classes.

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/resources/schema.sql linear-lite-server/src/main/resources/application.yml linear-lite-server/src/main/java/com/linearlite/server/dto/SendRegisterCodeRequest.java linear-lite-server/src/main/java/com/linearlite/server/dto/RegisterRequest.java linear-lite-server/src/main/java/com/linearlite/server/dto/LoginRequest.java linear-lite-server/src/main/java/com/linearlite/server/entity/EmailVerificationCode.java linear-lite-server/src/main/java/com/linearlite/server/mapper/EmailVerificationCodeMapper.java linear-lite-server/src/main/java/com/linearlite/server/entity/User.java
git commit -m "feat(auth): add email registration contract"
```

### Task 2: Backend verification-code and registration behavior

**Files:**
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/controller/AuthController.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/AuthService.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/EmailService.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/mapper/UserMapper.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/common/ApiResponse.java` if needed for message consistency
- Create: `linear-lite-server/src/test/java/com/linearlite/server/service/AuthServiceTest.java`

**Step 1: Write the failing service tests**

Cover:

- login with username
- login with email
- send-code rejects registered email
- send-code stores a fresh code and invalidates older unused ones
- register rejects wrong code
- register rejects expired code
- register creates user, marks code used, and returns login payload

**Step 2: Run tests to verify they fail**

Run: `cd linear-lite-server && ./mvnw -q -Dtest=AuthServiceTest test`

Expected: FAIL with missing service methods or unmet assertions.

**Step 3: Write minimal implementation**

- move auth logic out of controller into `AuthService`
- generate six-digit codes
- validate email/password/username
- use `JavaMailSender` behind `EmailService`
- create user and issue JWT

**Step 4: Run tests to verify they pass**

Run: `cd linear-lite-server && ./mvnw -q -Dtest=AuthServiceTest test`

Expected: PASS

**Step 5: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/controller/AuthController.java linear-lite-server/src/main/java/com/linearlite/server/service/AuthService.java linear-lite-server/src/main/java/com/linearlite/server/service/EmailService.java linear-lite-server/src/main/java/com/linearlite/server/mapper/UserMapper.java linear-lite-server/src/test/java/com/linearlite/server/service/AuthServiceTest.java
git commit -m "feat(auth): implement email registration flow"
```

### Task 3: Frontend API and auth store

**Files:**
- Modify: `src/services/api/types.ts`
- Modify: `src/services/api/auth.ts`
- Modify: `src/store/authStore.ts`
- Create: `src/store/authStore.test.ts`

**Step 1: Write the failing frontend tests**

Add tests for:

- login sends `identity`
- register and send-code APIs map correctly
- auth store persists session after register success

**Step 2: Run tests to verify they fail**

Run: `npm test -- src/store/authStore.test.ts`

Expected: FAIL because register methods and updated request types do not exist.

**Step 3: Write minimal implementation**

- add `LoginRequest.identity`
- add register/send-code API methods and DTOs
- extend auth store with `register`

**Step 4: Run tests to verify they pass**

Run: `npm test -- src/store/authStore.test.ts`

Expected: PASS

**Step 5: Commit**

```bash
git add src/services/api/types.ts src/services/api/auth.ts src/store/authStore.ts src/store/authStore.test.ts
git commit -m "feat(auth): add frontend registration api"
```

### Task 4: Frontend auth screen behavior

**Files:**
- Modify: `src/views/LoginView.vue`
- Add test if repo style fits: `src/views/LoginView.test.ts`

**Step 1: Write the failing UI test**

Cover:

- mode switch between login and register
- login submits identity
- register mode can send code
- register mode submits email, code, username, password

**Step 2: Run test to verify it fails**

Run: `npm test -- src/views/LoginView.test.ts`

Expected: FAIL because the UI still only supports username/password login.

**Step 3: Write minimal UI implementation**

- add login/register mode toggle
- change login input copy to `邮箱或用户名`
- add register form fields and send-code action
- show loading and inline error states for both actions

**Step 4: Run test to verify it passes**

Run: `npm test -- src/views/LoginView.test.ts`

Expected: PASS

**Step 5: Commit**

```bash
git add src/views/LoginView.vue src/views/LoginView.test.ts
git commit -m "feat(auth): add email registration ui"
```

### Task 5: Full verification

**Files:**
- No new code expected unless a failing integration test exposes a gap

**Step 1: Run backend auth tests**

Run: `cd linear-lite-server && ./mvnw -q test`

Expected: PASS

**Step 2: Run frontend tests**

Run: `npm test`

Expected: PASS

**Step 3: Smoke-check the app manually**

- register with a fresh email
- receive code via configured SMTP
- complete registration
- log out
- log in with email
- log in with username

**Step 4: Commit final polish if needed**

```bash
git add <changed-files>
git commit -m "test: cover email registration flow"
```
