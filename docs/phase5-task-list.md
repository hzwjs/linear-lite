# Phase 5 任务清单（交互模型重构与体验校准）

基于 `prd/phase5-ideas.md` 与 `phase5-implementation-plan.md` 拆解。

**图例**：`[ ]` 未开始 | `[~]` 进行中 | `[x]` 已完成

---

## 0. 前置约束

- 沿用 Phase 4 已有设计令牌、`CustomSelect`、`CustomDatePicker`、`CommandPalette`、`overlayStore`
- 不新增后端大功能，不以评论/活动流完整落地为前提
- 本阶段重点是前端交互模型与视图系统重构

---

## 1. Sprint 1：Issue 创建体验重构

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P5-1.1 | 梳理当前所有新建入口：顶部按钮、List 分组头、Board 列头、空状态、快捷键 C、Command Palette | [ ] | 无 |
| P5-1.2 | 新建 `IssueComposer` 组件，提供标题、描述、轻量属性条、创建按钮 | [ ] | P5-1.1 |
| P5-1.3 | 设计 `composerDefaults` 数据结构，用于注入默认 status / project 等上下文 | [ ] | P5-1.1 |
| P5-1.4 | 顶部 `New Issue` 接入 `IssueComposer` | [ ] | P5-1.2, P5-1.3 |
| P5-1.5 | List 分组头 `+` 接入 `IssueComposer`，并继承分组默认状态 | [ ] | P5-1.2, P5-1.3 |
| P5-1.6 | Board 列头 `+` 接入 `IssueComposer`，并继承列默认状态 | [ ] | P5-1.2, P5-1.3 |
| P5-1.7 | 快捷键 `C` 与 `CommandPalette` 新建命令接入 `IssueComposer` | [ ] | P5-1.2 |
| P5-1.8 | 空状态创建入口统一接入 `IssueComposer` | [ ] | P5-1.2 |
| P5-1.9 | 创建成功后任务落位正确，并可选连续创建（`Create more`） | [ ] | P5-1.4 ~ P5-1.8 |

---

## 2. Sprint 2：Issue Workspace 重构

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P5-2.1 | 评估并拆分当前 `TaskEditor` 中的“新建逻辑”和“详情逻辑” | [ ] | 无 |
| P5-2.2 | 重构 `TaskEditor` 为更接近 `IssueWorkspace` 的结构：顶部 / 主内容 / 属性区 | [ ] | P5-2.1 |
| P5-2.3 | 弱化大面积 overlay，保留主工作台上下文 | [ ] | P5-2.2 |
| P5-2.4 | 标题/描述改为阅读优先的内容体验，编辑状态后显 affordance | [ ] | P5-2.2 |
| P5-2.5 | 属性区保留轻量控件，弱化“整表单保存”感 | [ ] | P5-2.2 |
| P5-2.6 | 保持 `/tasks/:taskId` 深链接与直接打开能力 | [ ] | P5-2.2 |
| P5-2.7 | 创建成功后可选择直接打开新建任务的 `IssueWorkspace` | [ ] | P5-1.9, P5-2.2 |

---

## 3. Sprint 3：统一 View System

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P5-3.1 | 升级 `viewModeStore` 为统一 `viewConfig` 模型：layout / groupBy / orderBy / visibleProperties 等 | [ ] | 无 |
| P5-3.2 | 抽取 List / Board 共用的分组与排序逻辑 | [ ] | P5-3.1 |
| P5-3.3 | 将 Header 重构为更接近 View Bar 的结构 | [ ] | P5-3.1 |
| P5-3.4 | 将 `Board/List` 切换并入统一视图配置逻辑 | [ ] | P5-3.1 |
| P5-3.5 | 为 List / Board 接入统一 `visibleProperties` 控制 | [ ] | P5-3.1, P5-3.2 |
| P5-3.6 | 视图配置支持本地记忆（替换当前仅 layout 记忆） | [ ] | P5-3.1 |

---

## 4. Sprint 4：List View 重排

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P5-4.1 | 重排 List 行结构：左侧状态 affordance / 中部标题 / 右侧关键属性 | [ ] | P5-3.2 |
| P5-4.2 | 收缩 Pills 使用范围，priority 优先改为 icon 表达 | [ ] | P5-4.1 |
| P5-4.3 | 按 `visibleProperties` 动态显隐 ID / Status / Assignee / Project / Due Date / Updated | [ ] | P5-3.5 |
| P5-4.4 | 增加 hover / focus / selected 三态规则 | [ ] | P5-4.1 |
| P5-4.5 | 实现键盘导航：上下切换选中、Enter 打开、Esc 关闭 workspace | [ ] | P5-4.4, P5-2.2 |

---

## 5. Sprint 5：Board View 重排

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P5-5.1 | 强化 Board 列头结构：状态 icon / 名称 / count / menu / create | [ ] | P5-3.2 |
| P5-5.2 | TaskCard 进一步降噪，减少块感与组件感 | [ ] | P5-5.1 |
| P5-5.3 | Board 卡片字段接入 `visibleProperties` 控制 | [ ] | P5-3.5 |
| P5-5.4 | Board 列头 `+` 与 inline create 保持同一 `IssueComposer` 行为 | [ ] | P5-1.6, P5-5.1 |
| P5-5.5 | 保证从 Board 打开 issue 与从 List 打开 issue 进入同一 `IssueWorkspace` | [ ] | P5-2.2, P5-5.2 |

---

## 6. Sprint 6：状态管理与骨架收尾

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P5-6.1 | 拆分并整理 `currentTaskId`、`isComposerOpen`、`composerDefaults`、`viewConfig` 的状态归属 | [ ] | P5-1.x, P5-3.1 |
| P5-6.2 | 统一 overlay 优先级：Composer / Workspace / CommandPalette / 其他 Modal | [ ] | P5-1.2, P5-2.2 |
| P5-6.3 | 清理旧的“默认新建打开 TaskEditor”路径 | [ ] | P5-1.9, P5-2.2 |
| P5-6.4 | 回收 Phase 4 遗留的 UI 假设，避免双轨逻辑并存 | [ ] | P5-6.3 |

---

## 7. 验证与文档收尾

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P5-7.1 | 验证所有新建入口统一接入 `IssueComposer` | [ ] | P5-1.x |
| P5-7.2 | 验证分组/列上下文能正确继承默认状态 | [ ] | P5-1.5, P5-1.6 |
| P5-7.3 | 验证打开 issue 后主工作台上下文仍清晰可感知 | [ ] | P5-2.x |
| P5-7.4 | 验证 List / Board 共用同一视图配置逻辑 | [ ] | P5-3.x, P5-4.x, P5-5.x |
| P5-7.5 | 验证 List 键盘导航与 `Esc/Enter` 行为稳定 | [ ] | P5-4.5 |
| P5-7.6 | 更新相关文档，标注 Phase 5 覆盖 Phase 4 的 UX 假设 | [ ] | P5-6.4 |

---

## 完成标准

- [ ] 新建 Issue 默认进入轻量 `IssueComposer`，而非重型 `TaskEditor`
- [ ] 顶部、List、Board、快捷键、命令面板的新建路径统一
- [ ] 打开 Issue 进入阅读优先的 `IssueWorkspace`
- [ ] List / Board 共用统一 `viewConfig` 模型
- [ ] List 具备更稳定的高密度结构与键盘流
- [ ] Board 降低组件感，并与 List 对齐为同一视图系统

---

## 任务依赖简图

```text
P5-1.x (IssueComposer)
  -> P5-2.x (IssueWorkspace)
  -> P5-6.x (状态与骨架整理)

P5-3.x (View System)
  -> P5-4.x (List 重排)
  -> P5-5.x (Board 重排)

P5-1.x + P5-2.x + P5-3.x + P5-4.x + P5-5.x
  -> P5-7.x (验证与文档收尾)
```
