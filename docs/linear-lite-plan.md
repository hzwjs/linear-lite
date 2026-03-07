# Linear Lite MVP Implementation Plan

基于 `linear-lite-prd.md`，使用脑暴 (Brainstorming) 方法对需求进行深入拆解与技术规划。该产品旨在提供一个“轻量、少打断、快读反馈”的单页视图 Web 任务管理应用。

## User Review Required

> [!IMPORTANT]
>
> - PRD 中要求 MVP 为纯前端实现，未来方便迁移至 Spring Boot + MySQL。为此，我计划利用 `localStorage` 在前端持久化 mock 数据，以确保页面刷新不丢失状态，同时使用 Promise 模拟后端网络请求延迟，以便真实反映未来接入后端的 Loading/Error 状态。
> - UI 风格参考 Linear，根据系统要求，我们将使用 **Vanilla CSS / CSS Modules** 以及现代网页设计最佳实践（如深色模式优化、微交互等）来实现类似的高级感和流畅度，而不依赖冗余的外部 UI 框架（比如不使用 Element Plus 或 TailwindCSS，除非您明确要求）。
>   请确认以上技术选型与处理方式。

## Proposed Changes

### Phase 0: Project Bootstrap

- 初始化 Vite + Vue 3 + TS 骨架。
- 安装 Vue Router / Pinia / Vitest。
- 建立基础目录结构（`src/components`, `src/views`, `src/store`, `src/services`, `src/types` 等）、全局样式及测试脚手架。

### Phase 1: Domain + Mock Service

搭建基础结构与数据契约，确保与未来后端的无缝衔接。

#### [NEW] `src/types/domain.ts`

- 实体类型：`Task`
- 枚举类型：`Status` ('todo' | 'in_progress' | 'done')、`Priority` ('low' | 'medium' | 'high' | 'urgent')

#### [NEW] `src/services/taskService.ts`

- 核心 API：`listTasks`, `getTask`, `createTask`, `updateTask`, `transitionTask`。
- `localStorage` 持久化，并保证首次启动注入 seed 数据以便于立即预览。
- 维护 `updatedAt` 规则：在 Create/Update/Transition 时更新。
- 模拟 200-500ms 的网络请求延迟，提供合理的错误模拟（如通过开启特定 localStorage flag 或 query param 实现**可控失败注入**）与错误重试接口约定。

### Phase 2: Store + View State

管理核心业务数据与 UI 状态组合，提供单向数据流。

#### [NEW] `src/store/taskStore.ts` (基于 Pinia)

- **核心数据**：任务列表缓存 (`tasks`)。
- **状态维护**：当前选中任务 (`currentTask`)。
- **UI 状态**：严格控制并提供一致的 `loading` / `empty` / `error` (含重试控制) 三态反馈。
- **筛选中心**：
  - 搜索关键字（匹配 Title）。
  - 状态筛选（**单选优先**，对齐 PRD 的"优先实现单选"）。
  - 优先级筛选（单选）。

### Phase 3: Board + Editor

围绕“单页工作台”设计，明确各核心流程，特别是将“创建任务”提升为一等设计项。

#### [NEW] `src/views/BoardView.vue`

- 看板列表：横向铺开的三列看板结构（Todo, In Progress, Done），默认按 `updatedAt` 倒序展示。
- **空状态**：对于没有任何任务的数据集或筛选无结果的情况提供明确的空状态提示及创建入口。

#### [NEW] `src/components/TaskCard.vue`

- 单个任务展示卡片：显示 Title, Priority Icon, 状态、相关的关键时间或标记。
- 快速状态流转：通过卡片交互（如下拉菜单或状态切换按钮）完成原位状态切变而不用进入编辑页。

#### [NEW] `src/components/TaskEditor.vue` (兼顾创建与编辑)

- **创建模式**：从列表的空态入口或全局顶部的“新建任务”触发。强调必填项校验（如标题），创建后不仅立即落库 `localStorage`，还应迅速响应至对应的状态列，不强制页面刷新。
- **编辑模式**：点击任意任务卡片进入详情。支持深链接`/tasks/:taskId`打开 Drawer，同时背景保持主看板（"少打断"原则）。
- 支持标题和描述的实时或保存操作，以及底部操作栏快速切变状态、优先级。

### Phase 4: Routing + Verification

#### [NEW] `src/router/index.ts`

- 首页入口：`/` -> `BoardView`
- 详情入口：`/tasks/:taskId` -> 同由 `BoardView` 承接，利用 Router Viewer 嵌套组件弹出 `TaskEditor`，以便支持状态与路径剥离。

---

## 验证计划 (Verification Plan)

### Automated Tests

- 针对 Domain Model 增删改查逻辑构建单元测试。
- 基础的 `taskStore` Mutation 与 Action 逻辑验证。

### Manual Verification

1. **直接访问深链接验证**：在未加载出应用前直接在浏览器地址栏敲入 `/tasks/:taskId`，确认页面能加载看板 + 深链接指定的编辑器。
2. **列表与排序验证**：确认初始化默认按更新时间倒序排序逻辑是否生效。
3. **正向搜索与筛选验证**：在已有数据的列表页，输入关键词并从下拉框选择特定优先级或状态，验证列表能无刷新迅速且正确地过滤出符合条件的数据。
4. **空态验证**：清空所有缓存任务，或搜索不存在的值，验证应用层给出恰当空提示（且仍提供新任务入口项）。
5. **错误与重试验证**：开启可控失败注入（如设定特定标题触发失败），观察 Store 与 View 能否弹出重试入口并重新加载正常流转。
6. **创建、编辑及流转协同验证**：打开创建入口，填写标题后提交 -> 任务成功挂在待办下 -> 点击进入编辑详情 -> 更改标题和状态后退出 -> 主屏幕刷新状态卡列且排序更新正确。
