# Email Registration Design

**Goal:** Add real email-based account registration with verification codes, and allow users to sign in with either username or email plus password.

**Scope:** Backend registration and login updates, SMTP-based verification email sending, schema changes, frontend login/register UI, and automated tests.

## Current State

- Frontend login only accepts `username + password`
- Backend only exposes `POST /api/auth/login`
- `users` table has no `email`
- No registration flow, verification state, or email delivery config

## Chosen Approach

Use a two-step email verification flow backed by a dedicated verification-code table:

1. User requests a registration code with an email address
2. Backend stores the latest code with expiration and sends it by SMTP
3. User submits `email + code + username + password`
4. Backend verifies the code, creates the user, and returns the normal JWT login payload

Login is expanded to support `identity + password`, where `identity` can be either username or email.

This keeps incomplete registrations out of `users`, avoids cleanup of half-created accounts, and isolates verification concerns from the main user record.

## Data Model

### `users`

Add:

- `email VARCHAR(255) NOT NULL UNIQUE`

Keep:

- `username` unique
- `password` as the existing stored value for now

### `email_verification_codes`

Columns:

- `id`
- `email`
- `code`
- `purpose`
- `expires_at`
- `used_at`
- `created_at`

Rules:

- `purpose` is `register` for this phase
- only the newest unused code for the email and purpose is considered valid
- a successful registration marks the code as used
- a resend invalidates prior unused codes for the same email and purpose

## Backend API

### `POST /api/auth/login`

Request:

- `identity`
- `password`

Behavior:

- reject blank identity or password
- query user by email first when identity matches email semantics, otherwise allow username lookup
- if no user or password mismatch, return 401 with a single generic auth error
- on success return existing payload:
  - `token`
  - `userId`
  - `username`

### `POST /api/auth/register/send-code`

Request:

- `email`

Behavior:

- validate email format
- reject already-registered email
- generate a 6-digit numeric code
- persist a new verification record with expiration, for example 10 minutes
- send email by SMTP
- return success without exposing the code

### `POST /api/auth/register`

Request:

- `email`
- `code`
- `username`
- `password`

Behavior:

- validate required fields, email format, username uniqueness, password length, and registered-email uniqueness
- load the newest unused register code for the email
- reject missing, mismatched, expired, or used codes
- create the user
- mark the code as used
- issue JWT and return the same response shape as login

## Email Delivery

Use Spring Mail with environment-driven SMTP config in `application.yml`.

Required settings:

- `spring.mail.host`
- `spring.mail.port`
- `spring.mail.username`
- `spring.mail.password`
- `spring.mail.properties.mail.smtp.auth`
- `spring.mail.properties.mail.smtp.starttls.enable`
- sender address or fallback from username

Mail content can stay plain text for now:

- subject: `Your Linear Lite verification code`
- body includes the code and expiration window

## Frontend Flow

Use a single auth view with mode switching:

- `登录`
- `注册`

### Login mode

- input label/placeholder becomes `邮箱或用户名`
- password input stays unchanged
- submit calls updated login API with `identity`

### Register mode

Fields:

- email
- verification code
- username
- password

Actions:

- `发送验证码` button
- countdown lock during resend cooldown
- submit calls register API

On success:

- store JWT session through the existing auth store
- redirect to `/`

## Error Handling

Backend should return explicit business errors for:

- invalid email format
- email already registered
- username already exists
- password too short
- verification code invalid
- verification code expired
- verification email send failure

Frontend should surface backend messages inline in the auth card and disable duplicate submissions while pending.

## Testing

### Backend

- controller/service tests for login by username
- controller/service tests for login by email
- send-code rejects invalid and duplicate emails
- register rejects wrong or expired codes
- register creates user and marks code used
- SMTP sender is mocked in tests

### Frontend

- auth API tests for new request/response types
- auth store tests for login/register session handling
- login view tests for mode switching and correct payloads

## Out of Scope

- email login magic links
- password reset by email
- password hashing migration
- anti-abuse features beyond basic resend invalidation and validation
