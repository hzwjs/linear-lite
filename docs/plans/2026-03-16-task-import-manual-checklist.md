# Task Import Manual Checklist

## Preconditions

- Frontend runs with `npm run dev`
- Backend runs with `cd linear-lite-server && mvn spring-boot:run`
- User is logged in and has at least one active project

## Happy Path

1. Open the task board for a project.
2. Click `Import` in the top toolbar.
3. Upload a `.csv` file with columns:
   - `title`
   - `importId`
   - `description`
   - `status`
   - `priority`
   - `assignee`
   - `dueDate`
   - `parentImportId`
4. Confirm the auto-mapped fields on the mapping step.
5. Continue to preview.
6. Verify the preview shows the expected total, parent, and subtask counts.
7. Click `Import issues`.
8. Verify the modal shows created task keys and the board refreshes.

## Parent / Child Validation

1. Upload a file with one top-level task and one child task using `parentImportId`.
2. Complete the import.
3. Switch to list view with sub-issues enabled.
4. Verify the child appears under the correct parent.

## Error Validation

1. Upload a file with more than `800` rows.
2. Verify preview blocks submission and shows a row-limit error.
3. Upload a file with an unknown assignee username.
4. Verify preview shows an assignee validation error.
5. Upload a file with duplicate `importId`.
6. Verify preview shows a duplicate import ID error.
7. Upload a file with `parentImportId` pointing to a missing row.
8. Verify preview shows a missing parent error.

## Backend Validation

1. Submit a payload containing an invalid `assigneeId` or invalid `status`.
2. Verify backend rejects the request.
3. Confirm no partial tasks were created in the project.
