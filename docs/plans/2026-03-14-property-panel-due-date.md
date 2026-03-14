# Property Panel Due Date & Layout — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix Due Date calendar being clipped in the task detail right property panel, and set a min-width for the panel so popovers have room.

**Architecture:** (1) CustomDatePicker: render calendar panel via Vue Teleport to `body`, position with JS from trigger rect, flip left when right would overflow viewport. (2) TaskEditor: change `.editor-props` from `width: 220px` to `min-width: 260px`.

**Tech Stack:** Vue 3 (Composition API, Teleport), existing CSS in TaskEditor/CustomDatePicker.

**Design reference:** `docs/plans/2026-03-14-property-panel-due-date-design.md`

---

## Task 1: CustomDatePicker — Teleport panel and position with flip

**Files:**
- Modify: `src/components/ui/CustomDatePicker.vue`

**Step 1: Wrap panel in Teleport**

In `CustomDatePicker.vue` template, wrap the existing `div.custom-date-picker-panel` (the one with `id="date-picker-panel"`) in `<Teleport to="body">`. Keep the same ref and classes. Leave the trigger button outside Teleport.

**Step 2: Add reactive panel position**

- Add refs: `panelStyle = ref<{ top: string; left: string }>({ top: '0', left: '0' })`.
- Add a function `updatePanelPosition()` that:
  - If `!triggerRef.value || !panelRef.value || !isOpen.value` return.
  - Get `rect = triggerRef.value.getBoundingClientRect()`, `panelWidth = 240`, `panelHeight` e.g. 280 (or read from `panelRef.value.getBoundingClientRect()` after nextTick).
  - `const viewportW = window.innerWidth`, `viewportH = window.innerHeight`.
  - Default: `left = rect.left`, `top = rect.bottom + 4` (below trigger, 4px gap). If `rect.left + panelWidth > viewportW`, set `left = rect.right - panelWidth`. If `rect.bottom + panelHeight > viewportH` and `rect.top >= panelHeight`, set `top = rect.top - panelHeight - 4` (above trigger).
  - Set `panelStyle.value = { top: `${top}px`, left: `${left}px` }`.
- Use `panelStyle` on the panel div: `:style="panelStyle"` (merge with existing style if any). Panel must use `position: fixed` so the top/left are viewport-relative.

**Step 3: Call updatePanelPosition when opening and on resize/scroll**

- Import `nextTick` from `'vue'`. In `open()`, after `isOpen.value = true`, call `nextTick(() => updatePanelPosition())`.
- In `onMounted`, when `isOpen` is true, add listeners for `resize` and `scroll` (use `{ capture: true }` and listen on `window`, or `document`) that call `updatePanelPosition`. In `onUnmounted` (and when closing), remove them. Prefer a watcher on `isOpen`: when true, add listeners and call `updatePanelPosition`; when false, remove listeners. Use a single variable to hold the cleanup so it can be called from close and unmount.

**Step 4: Panel CSS**

- Ensure `.custom-date-picker-panel` has `position: fixed` and no longer uses `top: calc(100% + 4px); left: 0`. Remove those; position is fully driven by `panelStyle`.

**Step 5: Manual verification**

- Run app, open a task detail, open Due Date in the right property panel. Calendar should be fully visible (Mon–Sun and all dates) without horizontal scroll.
- Resize window so space to the right is small: calendar should flip to the left of the trigger.
- Commit: `feat(ui): CustomDatePicker teleport + flip so calendar is not clipped in narrow sidebar`

---

## Task 2: Property panel min-width

**Files:**
- Modify: `src/components/TaskEditor.vue` (lines ~1256–1265)

**Step 1: Change .editor-props width to min-width**

In `.editor-props` block, replace `width: 220px` with `min-width: 260px` and add `width: 260px` so the panel keeps a consistent width (or keep only `min-width: 260px` if you prefer it to grow; design suggests fixed feel, so `width: 260px` is fine).

**Step 2: Check media query**

In the existing `@media (max-width: 1100px)` block, `.editor-props` already has `width: auto`. Leave it as is so small viewports still get stacked layout.

**Step 3: Manual verification**

- At viewport width ≥ 1100px, right property column is 260px; Status/Priority/Assignee/Due Date all usable.
- At viewport width < 1100px, layout stacks; no horizontal squeeze.

**Step 4: Commit**

```bash
git add src/components/TaskEditor.vue
git commit -m "style(editor): property panel min-width 260px to avoid popover clip"
```

---

## Task 3: Document layout rule (optional)

**Files:**
- Modify: `docs/plans/2026-03-14-property-panel-due-date-design.md` (or add a short “Frontend / Property panel” note in `docs/`)

Add a one-sentence note under Part 2: “New controls in the property panel with wide popovers should use Teleport or limit width to avoid overflow clipping.” No code change; commit only if you add the line.

---

## Execution options

Plan complete and saved to `docs/plans/2026-03-14-property-panel-due-date.md`.

**1. Subagent-driven (this session)** — One subagent per task, review between tasks.  
**2. Parallel session (separate)** — New session with executing-plans in the same or a dedicated worktree.

Which approach do you want?
