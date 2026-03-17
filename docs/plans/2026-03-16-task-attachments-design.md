# Task Attachments Design

## Overview

任务详情内增加**独立附件列表**：与描述内容分离，由回形针按钮上传，支持任意文件类型（仅大小上限 10MB），在任务详情中展示、下载、删除。存储使用现有 Cloudflare R2 bucket，附件元数据存新表 `task_attachments`。

## Goals

- 用户通过任务详情中的回形针按钮上传文件，在独立「附件」区块中管理。
- 附件与描述内图片区分：描述内为 Markdown 图片，附件为列表资源。
- 复用现有 R2 与鉴权体系，权限与任务更新一致（项目成员）。

## Scope

- 本版：独立附件表、按任务的上传/列表/删除 API、回形针触发上传、附件列表区块、任意文件类型、10MB 单文件上限。
- 不做：预签名直传、上传进度条、删除时同步回收 R2（可首版只删 DB 或同步删 R2 二选一，设计里按同步删 R2 写）。

---

## 1. 数据模型与存储

### 表 `task_attachments`

- `id` BIGINT 自增主键
- `task_id` BIGINT NOT NULL（对应 `tasks.id`，应用层校验任务存在且当前用户有权限）
- `object_key` VARCHAR(512) NOT NULL（R2 对象 key，全局唯一）
- `file_name` VARCHAR(256) NOT NULL（原始文件名，展示与下载）
- `file_size` BIGINT NOT NULL（字节）
- `content_type` VARCHAR(128) DEFAULT NULL（MIME，可选）
- `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
- 索引：`idx_task_attachments_task_id ON task_attachments (task_id)`
- 不使用外键，关联由应用层维护

### R2

- 与描述内图片共用同一 bucket
- 附件 key：`attachments/{taskId}/{uuid}-{sanitized_filename}`，与 `task-images/` 区分
- 单文件大小上限：10MB

### 存储层扩展

- `ObjectStorageService` 增加通用上传方法（如 `uploadAttachment(MultipartFile file, long taskId)`），内部生成 key 并写入 R2，返回 url + key；或 `uploadFile(MultipartFile file, String keyPrefix)` 由调用方传入前缀
- 删除附件：应用层先删 DB 记录，再调 R2 删对象；R2 删除失败可只记日志仍返回 204

---

## 2. API

- **鉴权**：路径使用 `task_key`（如 `ENG-1`），即 `/api/tasks/{taskKey}/...`。需登录；上传/列表/删除前校验任务存在且当前用户为项目成员（与更新任务一致）。

- **上传**  
  - `POST /api/tasks/{taskKey}/attachments`  
  - Body：`multipart/form-data`，字段名 `file`，单文件  
  - 校验：文件非空、大小 ≤ 10MB；不校验 MIME  
  - 响应：`{ "id", "taskId", "objectKey", "fileName", "fileSize", "contentType", "url", "createdAt" }`  
  - R2 未启用时返回 503/400，提示存储未配置

- **列表**  
  - `GET /api/tasks/{taskKey}/attachments`  
  - 响应：`{ "data": [ { "id", "fileName", "fileSize", "contentType", "url", "createdAt" }, ... ] }`，按 `created_at` 升序

- **删除**  
  - `DELETE /api/tasks/{taskKey}/attachments/{attachmentId}`  
  - 校验附件属于该任务；先删 DB 再删 R2；R2 失败可记日志仍返回 204

- **下载**：不单独接口，列表与上传返回的 `url` 用于前端 `<a href={url} download={fileName}>`

---

## 3. 前端

- **回形针**：`content-actions` 中 Attach 按钮，点击触发隐藏 `input[type=file]`（可 multiple）；选文件后逐个 POST；上传前前端校验单文件 ≤ 10MB。仅 `mode === 'edit' && task` 时可用；若后端返回存储未配置，toast 并禁用回形针。

- **附件区块**：在 `content-actions` 与 Sub-issues 之间，与 Sub-issues 同风格（可折叠「Attachments」+ 数量）；仅编辑态且有任务时展示。列表项：文件名、大小、时间、下载（用 `url`）、删除。空状态："No attachments. Use the paperclip to add one."

- **上传态**：选文件后列表顶部插入占位项（文件名 + "Uploading…"），成功用返回数据替换，失败同项显示 "Upload failed" 与重试/移除；或首版不做占位，全部完成后重新 GET 列表。

- **数据**：进入任务详情（编辑态）时 GET 附件列表；上传/删除后更新本地列表或重新拉取。新建任务无附件区。

---

## 4. 权限与错误

- 权限：已登录 + 任务存在 + 当前用户为项目成员；否则 403/404。
- 上传：空文件 400；超 10MB 400；R2 不可用 503/500。
- 删除：附件不存在或不属于任务 404；R2 删除失败记日志，仍返回 204（或约定 500）。
- 前端：网络/未知错误用现有 API 错误处理（toast）；失败占位可重试/移除。

---

## 5. 测试要点

- 后端：上传合法文件 200 及正确 body；空/超限 400；无权限 403；列表仅该任务、升序；删除后 DB 与 R2 一致。
- 前端：回形针仅编辑态可用；选文件后占位或刷新列表；列表展示与下载/删除正确；超 10MB 提示。
- E2E（可选）：编辑任务 → 回形针上传 → 列表出现 → 下载/删除。
