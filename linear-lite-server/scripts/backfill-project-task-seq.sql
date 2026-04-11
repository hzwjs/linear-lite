-- Backfill project_task_seq.next_number for existing projects.
-- Rule: next_number = current max task_key number + 1 (minimum 1).
-- Safe to run repeatedly.

INSERT INTO project_task_seq (project_id, next_number)
SELECT
  p.id AS project_id,
  GREATEST(
    1,
    COALESCE(
      (
        SELECT MAX(CAST(SUBSTRING(t.task_key, LENGTH(p.identifier) + 2) AS UNSIGNED)) + 1
        FROM tasks t
        WHERE t.project_id = p.id
          AND t.task_key LIKE CONCAT(p.identifier, '-%')
      ),
      1
    )
  ) AS next_number
FROM projects p
ON DUPLICATE KEY UPDATE
  next_number = GREATEST(project_task_seq.next_number, VALUES(next_number));
