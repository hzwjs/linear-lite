# Task Import Design

## Overview

为 Linear Lite 设计第一版“任务导入”能力，支持用户在当前激活项目中导入 `.csv` / `.xlsx` 文件，批量新建任务，并建立父子任务关系。

本版范围：

- 支持文件类型：`.csv`、`.xlsx`
- 支持字段：`title`、`description`、`status`、`priority`、`assignee`、`dueDate`
- 支持父子关系：`importId`、`parentImportId`
- 只支持新增任务，不更新已有任务
- 仅作用于当前激活项目
- 单次导入上限：`800` 行

不在本版范围：

- 更新已有任务
- 跨项目导入
- 附件、评论、标签、自定义字段
- 部分成功导入
- 任意复杂 Excel 智能推断

## Goals

- 让用户可以从 Excel / CSV 快速导入任务
- 在导入前看到字段映射和错误预览
- 以事务方式一次性创建父任务与子任务，避免半成功状态
- 与现有任务创建和任务列表刷新机制保持一致

## Current Context

当前前端已有单条任务创建能力：

- 任务页入口在 `src/views/BoardView.vue`
- 创建弹层在 `src/components/IssueComposer.vue`
- 前端任务状态由 `src/store/taskStore.ts` 管理
- 后端现有单条创建接口为 `POST /api/tasks`
- 后端创建逻辑位于 `linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java`

当前系统没有批量导入入口，也没有批量创建接口。

## User Flow

导入入口位于任务页顶部操作区，与现有新建任务入口同级。导入绑定当前激活项目，不在文件内选择项目。

用户流程分为 4 步：

1. 上传文件
2. 字段映射
3. 导入预览
4. 执行导入并展示结果

### Step 1: 上传文件

- 支持拖拽或点击上传
- 限制文件扩展名为 `.csv`、`.xlsx`
- 显示模板下载入口
- 超过 `800` 行时直接阻止继续

### Step 2: 字段映射

- 系统根据列名尝试自动映射
- 用户确认每一列对应的目标字段
- 若缺少必填字段，禁止进入下一步

### Step 3: 导入预览

- 展示将创建的任务总数、父任务数、子任务数
- 列出所有错误行及原因
- 错误未清空前不可执行导入

### Step 4: 执行导入

- 前端提交结构化导入请求
- 后端在单个事务中完成创建
- 成功后显示摘要并刷新任务列表

## File Format

第一版提供明确模板，推荐列如下：

- `title`：必填
- `description`：可选
- `status`：可选
- `priority`：可选
- `assignee`：可选，填写用户名
- `dueDate`：可选，格式 `YYYY-MM-DD`
- `importId`：必填，文件内唯一标识
- `parentImportId`：可选，指向另一行的 `importId`

### Why `importId`

父子关系不使用父任务标题匹配。标题可能重复，也可能在导入前后被修改。使用 `importId` / `parentImportId` 可以在不依赖数据库 ID 的前提下稳定表达文件内关系，适合“仅新增”的第一版。

## Validation Rules

前后端都需要校验，前端用于预览和减少试错，后端用于最终兜底。

### File-level rules

- 文件不能为空
- 文件类型必须为 `.csv` 或 `.xlsx`
- 行数不得超过 `800`
- 必须存在 `title` 和 `importId` 两个映射结果

### Row-level rules

- `title` 为空：报错
- `importId` 为空或重复：报错
- `status` 非法：报错
- `priority` 非法：报错
- `assignee` 找不到对应用户名：报错
- `dueDate` 无法解析：报错
- `parentImportId` 找不到对应行：报错
- `parentImportId` 指向自身：报错

### Relationship rules

- 允许多层级父子关系
- 不允许形成环
- 同一批导入中的父子关系必须完全闭合

## Approaches Considered

### Option A: 前端解析文件，后端提供批量导入接口

优点：

- 体验完整，可做字段映射和预览
- 后端可用事务保证一致性
- 适合父子任务两阶段创建

缺点：

- 需要同时改前端与后端

### Option B: 前端解析文件，逐条调用现有创建接口

优点：

- 后端改动少

缺点：

- 父子任务依赖多轮请求拼接
- 失败后容易半成功
- 性能和可恢复性差

### Option C: 后端直接接收文件并解析

优点：

- 前端较轻

缺点：

- 需要文件上传、多格式解析和更多后端依赖
- 字段映射和预览体验较差

### Recommendation

选择 Option A。它在一致性、用户体验和当前项目复杂度之间最平衡。

## Architecture

### Frontend Responsibilities

前端负责：

- 文件读取和解析
- 字段自动映射与人工确认
- 本地预校验
- 导入预览展示
- 将结构化数据提交给后端

建议新增：

- `src/components/TaskImportModal.vue`
- `src/utils/taskImport.ts`
- `src/utils/taskImport.test.ts`
- `src/services/api/task.ts` 中的导入 API 方法

### Backend Responsibilities

后端负责：

- 接收结构化导入请求
- 校验项目、用户、状态、优先级和父子关系
- 事务化创建所有任务
- 返回导入结果摘要

建议新增：

- `POST /api/tasks/import`
- 导入请求 / 响应 DTO
- `TaskService` 中的批量导入方法
- 相关单测

## API Design

建议接口：

`POST /api/tasks/import`

请求体示意：

```json
{
  "projectId": 1,
  "rows": [
    {
      "lineNumber": 2,
      "importId": "EPIC-1",
      "parentImportId": null,
      "title": "Set up onboarding",
      "description": "Create checklist",
      "status": "todo",
      "priority": "high",
      "assigneeId": 3,
      "dueDate": "2026-03-20T00:00:00"
    }
  ]
}
```

成功响应示意：

```json
{
  "createdCount": 12,
  "parentCount": 5,
  "subtaskCount": 7,
  "taskKeys": ["ENG-11", "ENG-12"]
}
```

失败响应应返回结构化错误列表，至少包含：

- `lineNumber`
- `field`
- `message`

## Import Execution Model

后端导入采用两阶段创建：

1. 第一阶段创建所有任务，暂不设置 `parent_id`
2. 建立 `importId -> task.id` 映射
3. 第二阶段按 `parentImportId` 回填 `parent_id`
4. 任意一步失败则整批回滚

这样可以：

- 不依赖文件顺序
- 支持多层级父子关系
- 避免部分成功导致的数据不一致

## Error Handling

第一版采用“整批成功或整批失败”。

原则：

- 可在前端发现的问题尽量前置到预览页
- 后端始终做最终校验
- 导入执行失败时不落任何任务数据

## Testing Strategy

### Frontend

- 字段自动映射
- 状态 / 优先级 / 日期解析
- `importId` 唯一性校验
- `parentImportId` 关系校验
- 800 行限制

### Backend

- 成功导入父子任务
- `assignee` / `assigneeId` 非法
- 非法状态或优先级
- 缺失父任务引用
- 自环或环形关系
- 任意失败触发事务回滚

### Manual Verification

- 上传合法 CSV，完成导入，任务列表刷新
- 上传含父子关系的 XLSX，验证层级正确
- 上传超过 800 行文件，前端阻止继续
- 上传含非法用户或非法状态的文件，预览显示错误

## Open Product Decisions Already Resolved

- 来源：仅 Excel / CSV
- 字段范围：基础字段 + 父子任务
- 导入行为：只新增，不更新
- 行数限制：800 行以内

## Next Step

下一步进入实现计划，按 TDD 拆分前端解析、前端弹层、后端接口、事务导入和验证。
