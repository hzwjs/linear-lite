# Avatar Display (Initials + Color) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** When a user has no avatar_url, show up to 2 initials plus a stable per-user background color so assignees are easier to tell apart (e.g. same surname no longer all look the same).

**Architecture:** Add a small util `src/utils/avatar.ts` with `getInitials(name)` and `getAvatarColor(userId)`. List and card use it for assignee fallback; activity stream uses `getInitials` only (no color, no actorId). Existing avatar_url path unchanged.

**Tech Stack:** Vue 3, TypeScript, Vitest, scoped CSS

**Design:** `docs/plans/2026-03-16-avatar-display-design.md`

---

### Task 1: Avatar utils and tests

**Files:**
- Create: `src/utils/avatar.ts`
- Test: `src/utils/avatar.test.ts`

**Step 1: Write the failing test**

In `src/utils/avatar.test.ts`:
- `getInitials`: `'John Doe'` → `'JD'`, `'黄志文'` → `'黄志'`, `'Alice'` → `'AL'`, `'A'` → `'A'`, `'Unassigned'` / `''` → `'—'`, single space-separated word with 2+ chars → first two letters uppercase.
- `getAvatarColor`: same userId always returns same `background` and `color`; different userIds return different `background`; `color` is either `#fff` or dark gray for contrast.

**Step 2: Run test to verify it fails**

Run: `npx vitest run src/utils/avatar.test.ts`
Expected: FAIL (module/function not found).

**Step 3: Implement avatar utils**

In `src/utils/avatar.ts`:
- `getInitials(name: string): string` — if no name or `Unassigned`, return `'—'`. If name contains space: take first letter of first word + first letter of second word (uppercase); if one word, first 2 chars uppercase (or 1 if length 1). If no space: first 2 characters (or 1).
- `getAvatarColor(userId: number): { background: string; color: string }` — hash userId to hue (e.g. `((id * 2654435761) >>> 0) % 360`), use fixed S/L (e.g. 65%, 45%), convert to hex; set `color` to `#fff` if background is dark else `#374151`.

**Step 4: Run test to verify it passes**

Run: `npx vitest run src/utils/avatar.test.ts`
Expected: PASS.

**Step 5: Commit**

```bash
git add src/utils/avatar.ts src/utils/avatar.test.ts
git commit -m "feat(avatar): add getInitials and getAvatarColor utils"
```

---

### Task 2: TaskListView assignee fallback

**Files:**
- Modify: `src/components/TaskListView.vue`

**Step 1: Use getInitials and getAvatarColor**

- Import `getInitials` and `getAvatarColor` from `@/utils/avatar`.
- Replace `assigneeInitial(row.task)` with `getInitials(assigneeName(row.task))`.
- For the fallback span (when has assignee but no avatar_url), add inline style for background and color from `getAvatarColor(assigneeUserId)`. Need to get assignee user id: from `row.task.assigneeId` (already available).
- Keep `.avatar-18.fallback` but override background/color via inline style when assigned (so dynamic color wins).

**Step 2: Adjust fallback styles**

- In scoped CSS, ensure `.avatar-18.fallback` does not force a single background when style is set (e.g. avoid `background: var(--color-border)` when inline style present; use fallback only for unassigned if needed). Prefer: assigned fallback uses inline style only; unassigned keeps current gray.

**Step 3: Run build and quick smoke**

Run: `npm run build`
Expected: success. Manually open list view and confirm assignee column shows 2 initials and colored circles where no avatar.

**Step 4: Commit**

```bash
git add src/components/TaskListView.vue
git commit -m "feat(avatar): list view use initials + color for assignee fallback"
```

---

### Task 3: TaskCard assignee fallback

**Files:**
- Modify: `src/components/TaskCard.vue`

**Step 1: Use getInitials and getAvatarColor**

- Import `getInitials` and `getAvatarColor` from `@/utils/avatar`.
- Replace `assigneeInitial` computed with one that uses `getInitials(assignee.value?.username ?? 'Unassigned')`.
- For the assignee avatar fallback span (no img): set inline style from `getAvatarColor(assignee.value!.id)` when assignee exists; keep placeholder style when unassigned.

**Step 2: Run build**

Run: `npm run build`
Expected: success.

**Step 3: Commit**

```bash
git add src/components/TaskCard.vue
git commit -m "feat(avatar): card view use initials + color for assignee fallback"
```

---

### Task 4: Activity stream use getInitials

**Files:**
- Modify: `src/utils/taskActivity.ts`
- Test: `src/utils/taskActivity.test.ts`

**Step 1: Delegate getActivityAvatarLabel to getInitials**

In `taskActivity.ts`: import `getInitials` from `@/utils/avatar`. In `getActivityAvatarLabel`, return `getInitials(trimmed)` when trimmed non-empty; return `'?'` for empty (keep current empty behavior).

**Step 2: Update taskActivity tests**

In `taskActivity.test.ts`: update expectations so `getActivityAvatarLabel('Admin User')` → `'AU'` (first letter of each word), `getActivityAvatarLabel('alice')` → `'AL'` (first 2 chars of single word). Empty still `'?'`.

**Step 3: Run tests**

Run: `npx vitest run src/utils/taskActivity.test.ts` and `npx vitest run src/utils/avatar.test.ts`
Expected: PASS.

**Step 4: Commit**

```bash
git add src/utils/taskActivity.ts src/utils/taskActivity.test.ts
git commit -m "feat(avatar): activity avatar label use getInitials (2 chars)"
```

---

### Task 5: Final verification

**Files:** none

**Step 1: Run full test suite**

Run: `npx vitest run`
Expected: all pass.

**Step 2: Build**

Run: `npm run build`
Expected: success.

**Step 3: Commit (if any doc or tweak)**

Optional: add one-line note in design doc that implementation is done, then commit. Otherwise no commit.
