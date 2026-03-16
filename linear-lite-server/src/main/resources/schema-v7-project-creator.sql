USE linear_lite;

ALTER TABLE projects
    ADD COLUMN creator_id BIGINT NOT NULL DEFAULT 1 AFTER identifier;

CREATE INDEX idx_projects_creator_id ON projects (creator_id);

UPDATE projects
SET creator_id = 1
WHERE creator_id IS NULL OR creator_id = 0;
