# Daily Summary Email Refresh Design

## Context

The current daily summary email works functionally but has four clear product issues:

1. The visual design leans on a saturated purple accent that reads less professional than intended.
2. Task statuses are rendered as raw backend enum values such as `in_progress` and `todo`.
3. Task rows do not show current task progress.
4. The footer CTA button can render an unusable link when the public base URL is missing or malformed.

This work should improve the email as a lightweight operational digest: modern, young, bright, and easy to scan, while still behaving like a transactional email rather than a marketing page.

## Goals

- Refresh the HTML email visual language to feel younger, cleaner, and lighter.
- Translate backend task statuses into Chinese user-facing labels.
- Show per-task progress using the real `progressPercent` value.
- Make footer and task links resolve to valid absolute URLs when the application base URL is configured.
- Keep the plain-text email consistent with the HTML content.

## Non-Goals

- Introducing a new template engine.
- Reworking dispatch, scheduling, or mail sending behavior.
- Adding richer analytics, charts, avatars, or complex responsive components.
- Changing existing task status semantics.

## Recommended Approach

Apply a focused upgrade inside the existing composer path:

- Extend the daily summary DTO/query pipeline to carry `progressPercent`.
- Update `DigestMailComposer` to translate status, render progress, and produce safer absolute links.
- Refresh the HTML structure and palette without changing the service boundary.
- Expand composer tests to lock the new output contract.

This keeps the change small and reviewable while fixing the full user-visible problem.

## Data Contract Changes

### DailySummaryTaskDto

Add:

- `Integer progressPercent`

### Query / mapping expectations

The task query that populates daily summary items must select the task table's `progress_percent` column and map it into the DTO. The value should be treated as the source of truth for email rendering.

## Content Model

### Status translation

Use the following mapping for both HTML and plain text output:

- `backlog` -> `待规划`
- `todo` -> `待处理`
- `in_progress` -> `进行中`
- `in_review` -> `待审核`
- `done` -> `已完成`
- `canceled` -> `已取消`
- `duplicate` -> `重复任务`

Unknown or blank values should fall back to `未设置` rather than emitting the raw enum.

### Progress rendering

For each task row:

- Show `progressPercent` as `N%` when non-null.
- When `progressPercent` is null, render `--`.
- Do not infer a synthetic value from status in this email layer; the backend task model already owns status-progress linkage.

### Link behavior

All task links and the footer CTA must be built from a normalized public base URL.

Rules:

- Strip trailing `/` from the configured base URL.
- Footer CTA target: `<baseUrl>/`
- Task target: `<baseUrl>/projects/{projectId}/tasks/{taskKey}`
- When the base URL is blank, do not emit a broken clickable CTA. Render a disabled-looking text fallback in HTML and a plain informational line in text mode.

## Visual Design

### Tone

- Young
- Modern
- Bright
- Lightweight
- Not heavy or oppressive

### Palette

Replace the current purple-dominant styling with a fresher blue-cyan accent system:

- Page background: soft neutral (`#f4f7fb` range)
- Card background: white
- Primary text: dark neutral (`#0f172a` range)
- Secondary text: cool gray (`#64748b` range)
- Primary accent: blue-cyan (`#2563eb` / `#0ea5e9` family)
- Overdue accent: warm red (`#dc2626` family)
- Borders/dividers: light neutral (`#e2e8f0` range)

Avoid large dark blocks and avoid returning to a purple-first theme.

### Layout

Keep the email as a single centered content column suitable for desktop email clients.

Sections:

1. Brand header
2. Greeting and title
3. Compact summary strip
4. Overdue section
5. Due today section
6. Footer CTA
7. Footer note

### Summary strip

Present a compact horizontal summary with:

- Business date
- Total open items in this digest
- Overdue count
- Due today count

This should read as an operational snapshot, not decorative marketing content.

### Task row presentation

Each task row should contain:

- Task key as the primary clickable anchor
- Task title beside or under the key
- Metadata line with due time, translated status, and progress
- Clean spacing and clear separators for scanability

Progress should be explicit text, for example `进度 65%`, not a decorative progress bar. This keeps rendering stable across mail clients.

## HTML Rendering Requirements

- Use table-safe layout patterns compatible with email clients.
- Preserve white background card layout.
- Use restrained radius and shadows; no oversized hero treatment.
- Keep clickable affordances obvious through color contrast and underline-free link treatment.
- Ensure long titles wrap instead of overflowing.
- Avoid nested decorative cards inside the main card.

## Plain Text Rendering Requirements

The text version must stay structurally aligned with the HTML version:

- Include translated status labels.
- Include progress values for each task.
- Include working task URLs when base URL is configured.
- Include a non-broken fallback line when base URL is absent.

## Error Handling

- Missing or blank `publicBaseUrl` must not produce malformed `href` values such as relative or empty links in the email body.
- Unknown status values must not leak raw backend enums if a friendly label cannot be resolved.
- Null progress must remain visible as unavailable rather than causing formatting issues.

## Testing

Add or update focused unit tests around `DigestMailComposer` to cover:

1. Status translation in HTML and text output.
2. Progress rendering with a real `progressPercent`.
3. Null progress fallback.
4. Correct task and footer links when `publicBaseUrl` is configured.
5. Safe degraded output when `publicBaseUrl` is blank.
6. Existing escaping behavior for task titles.

If a query-layer test already exists for the daily summary DTO mapping, extend it to assert `progressPercent`. Otherwise, keep verification focused at the composer and mapper boundary touched by this change.

## Implementation Scope

Expected files:

- `linear-lite-server/src/main/java/com/linearlite/server/dto/DailySummaryTaskDto.java`
- the daily summary query mapper/service path that selects task rows
- `linear-lite-server/src/main/java/com/linearlite/server/service/DigestMailComposer.java`
- `linear-lite-server/src/test/java/com/linearlite/server/service/DigestMailComposerTest.java`

## Risks and Tradeoffs

- Staying with string-built HTML keeps the change small but leaves template maintenance somewhat manual.
- Rendering progress as text is less expressive than a visual meter, but much safer across email clients.
- Degrading to a non-clickable fallback when `publicBaseUrl` is absent avoids broken links, but also makes configuration gaps more visible. That is desirable here.

## Acceptance Criteria

- The email no longer uses the current purple-heavy styling.
- Status values are shown in Chinese labels, not backend enums.
- Each task row shows progress when available.
- Footer CTA and task links are valid absolute URLs when `app.public-base-url` is set.
- Blank base URL does not render a broken CTA link.
- HTML and text outputs remain covered by automated tests.
