# Project Invitations Design

**Goal:** Add project-level invitations by email, persist project participants, and restrict project visibility so users only see projects they participate in.

**Scope:** Backend member/invitation data model, project visibility and permission checks, invitation API, automatic acceptance during auth, frontend project invite entrypoint, and tests.

## Current State

- Projects only store `creatorId`
- `/api/projects` returns all projects to every authenticated user
- Task queries trust the incoming `projectId` and do not verify project membership
- No invitation or member concept exists

## Chosen Approach

Use two dedicated tables:

- `project_members` for active project membership
- `project_invitations` for email invitations that may exist before a user registers

This keeps visibility checks simple: every “can see this project?” question resolves through `project_members`. Invitations stay separate until accepted, avoiding mixed row semantics and complicated unique constraints.

## Data Model

### `project_members`

Columns:

- `id`
- `project_id`
- `user_id`
- `role`
- `created_at`

Rules:

- unique on `(project_id, user_id)`
- creator is automatically inserted as a member during project creation
- `role` can start with `owner` for creator and `member` for invitees

### `project_invitations`

Columns:

- `id`
- `project_id`
- `email`
- `invited_by`
- `accepted_at`
- `created_at`

Rules:

- only one active invitation per `(project_id, email)`
- email is normalized to lowercase
- when accepted, the matching user is inserted into `project_members` and `accepted_at` is set

## Backend Flow

### Project listing

- `GET /api/projects` only returns projects joined by the current user
- list order can stay by project id ascending

### Project creation

- create the project as today
- immediately insert creator into `project_members` as `owner`

### Invitation

`POST /api/projects/{id}/invitations`

Request:

- `email`

Behavior:

- require current user to be a member of the project
- for this phase, only creator can invite to keep permission boundaries tight
- reject invalid email
- reject duplicate member or duplicate active invite
- if email matches an existing user, insert membership immediately and optionally skip pending invite
- otherwise create a pending invitation row
- send an email notice

### Invitation acceptance during auth

- after successful registration, accept all pending invitations for that email
- after successful login, run the same acceptance step again to catch invitations sent after registration
- acceptance inserts rows into `project_members` when missing, then marks invitations accepted

### Permission checks

Add membership checks for:

- list tasks by project
- create task
- import tasks
- update project
- delete project
- invite members

Delete remains creator-only even though visibility is member-based.

## Frontend Flow

### Project sidebar

- existing project list continues to render from `/projects`
- now it naturally shows only projects the user participates in
- when the user has zero projects, show the empty state for “no participating projects”

### Project settings

Add invite UI to the project settings modal:

- one email input
- one invite button
- inline success/error message

No separate invite-management page is needed in this phase.

## Error Handling

Explicit backend errors for:

- invalid email
- project not found
- non-member access
- non-owner invite attempts
- email already belongs to a project member
- email already has an active invite
- invitation email send failure

## Testing

### Backend

- project creator becomes member on create
- project list returns only current user memberships
- invite existing user adds membership immediately
- invite unknown email creates pending invitation
- login/register consumes pending invitations
- non-members cannot read or mutate project/task data

### Frontend

- project API invitation request
- project store still sets active project from filtered list
- project settings invite action renders and calls API

## Out of Scope

- invitation revocation
- member removal
- member role editing
- accept/decline invitation inbox UI
