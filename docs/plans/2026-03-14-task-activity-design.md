# Task Activity Design

**Goal:** Add real Activity history to task details so task edits appear as durable events instead of a static placeholder.

**Scope:** Record these events: task creation, title change, description change, status change, priority change, assignee change, due date change, favorite, unfavorite.

## Root Cause

The current Activity section in the task detail workspace is static UI. It only renders a derived "created the issue" line from task metadata and has no backing event model, storage, or API. Because no activity records are created during task mutations, later edits cannot appear in the timeline.

## Chosen Approach

Use a persisted backend event log:

- Add a `task_activities` table keyed by `task_id`
- Record structured events from backend services when tasks are created, updated, favorited, or unfavorited
- Expose `GET /api/tasks/{taskKey}/activities`
- Render the returned events in the Activity section

This preserves history across refreshes and keeps event ordering, actor attribution, and future extensibility.

## Data Model

`task_activities` columns:

- `id`
- `task_id`
- `user_id`
- `action_type`
- `field_name`
- `old_value`
- `new_value`
- `created_at`

`action_type` values for this phase:

- `created`
- `changed`
- `favorited`
- `unfavorited`

For `changed`, `field_name` identifies the field (`title`, `description`, `status`, `priority`, `assigneeId`, `dueDate`).

## Backend Flow

- `TaskService.create(...)` writes one `created` event after insert
- `TaskService.update(...)` compares old and new values and writes one `changed` event per actual field mutation
- `TaskService.addFavorite(...)` writes `favorited`
- `TaskService.removeFavorite(...)` writes `unfavorited`
- `TaskActivityService.listByTaskKey(...)` resolves actor names from `users`

## Frontend Flow

- Opening task detail fetches `/tasks/{taskKey}/activities`
- The Activity block renders all returned events in chronological display order
- Relative timestamps remain UI-derived from `createdAt`
- Comment input stays read-only and out of scope

## Event Text Rules

- `created`: `{actor} created the issue`
- `changed status`: `{actor} changed status from {old} to {new}`
- `changed priority`: `{actor} changed priority from {old} to {new}`
- `changed assigneeId`: `{actor} changed assignee from {old} to {new}`
- `changed dueDate`: `{actor} changed due date from {old} to {new}`
- `changed title` / `description`: `{actor} changed title` / `{actor} changed description`
- `favorited`: `{actor} favorited the issue`
- `unfavorited`: `{actor} removed the issue from favorites`

## Testing

- Backend unit tests for event creation on create/update/favorite flows
- Backend tests for "no actual change" producing no event
- Frontend API tests for activity list mapping
- Frontend formatting tests for user-facing activity copy
