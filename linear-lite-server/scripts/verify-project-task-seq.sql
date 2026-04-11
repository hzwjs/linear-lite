-- Verification queries for project_task_seq correctness.
-- Run after backfill and before enabling write traffic.

-- 1) Missing sequence rows (should return 0 rows).
SELECT p.id AS project_id, p.identifier
FROM projects p
LEFT JOIN project_task_seq s ON s.project_id = p.id
WHERE s.project_id IS NULL;

-- 2) next_number too small (should return 0 rows).
SELECT
  p.id AS project_id,
  p.identifier,
  s.next_number,
  COALESCE(
    (
      SELECT MAX(CAST(SUBSTRING(t.task_key, LENGTH(p.identifier) + 2) AS UNSIGNED)) + 1
      FROM tasks t
      WHERE t.project_id = p.id
        AND t.task_key LIKE CONCAT(p.identifier, '-%')
    ),
    1
  ) AS expected_min_next_number
FROM projects p
JOIN project_task_seq s ON s.project_id = p.id
WHERE s.next_number < COALESCE(
  (
    SELECT MAX(CAST(SUBSTRING(t.task_key, LENGTH(p.identifier) + 2) AS UNSIGNED)) + 1
    FROM tasks t
    WHERE t.project_id = p.id
      AND t.task_key LIKE CONCAT(p.identifier, '-%')
  ),
  1
);

-- 3) Duplicate task_key check (should return 0 rows).
SELECT task_key, COUNT(*) AS dup_count
FROM tasks
GROUP BY task_key
HAVING COUNT(*) > 1;
