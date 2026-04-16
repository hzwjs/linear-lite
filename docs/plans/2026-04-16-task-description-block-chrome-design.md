# 任务描述 Block 侧栏与 Slash 菜单设计

## 概述

任务详情中的**描述编辑器**当前使用 `BlockNoteViewRaw`，仅挂载 `@` 的 `SuggestionMenuController`，因此缺少常见块编辑器能力：块左侧 **「+」插入**、**拖拽手柄**，以及输入 **`/`** 时的 **块类型 / 插入命令面板**。本设计在**不改变描述存储格式**（仍为 BlockNote JSON 字符串）的前提下，仅对**任务描述**这一处开启 BlockNote 官方提供的块级 UI（下称 **block chrome**）。评论、新建 Issue、内联回复等其它 `BlockNoteEditorWrapper` 实例保持精简行为。

## 已确认决策

| 项 | 选择 |
|----|------|
| 作用范围 | **A**：仅 `TaskEditor.vue` 中任务**描述**区域的编辑器 |
| 实现策略 | **方案 1**：在现有 `BlockNoteViewRaw` 上按需挂载官方侧栏与 slash 相关子组件；通过 `BlockNoteEditorWrapper` 布尔 prop 控制，默认关闭 |
| 存储 | 不新增格式；slash 与侧栏仅改变块树结构，落库仍为现有 `v-model` JSON |

## 1. 目标与成功标准

- 仅在描述编辑器可见：`/` 打开块命令菜单；块左侧出现 **+** 与 **拖拽**（具体能力与 BlockNote **0.48** 默认实现一致）。
- 评论、Issue Composer、回复等入口**不出现**上述 UI，交互与现网一致。
- 保存、刷新、与 Markdown 遗留内容的加载路径**不受影响**（沿用 `blockNoteDescription` 与现有自动保存逻辑）。

## 2. 架构与数据流

- **Vue**：`TaskEditor.vue` 描述区 `<BlockNoteEditorWrapper … :blockChrome="true" />`（或等价绑定）；其余调用处不传或 `false`。
- **React**：`BlockNoteEditorReact` 增加 prop `blockChrome?: boolean`（默认 `false`）。为 `true` 时，在 `BlockNoteViewRaw` 的 `children` 中与现有 `@` `SuggestionMenuController` **并列**挂载 slash 与侧栏所需组件。
- **数据**：仍由 `BlockNoteEditorWrapper` 的 `v-model` 与 `@change` 同步 JSON；无新 API、无后端变更。

## 3. BlockNote API 对接（实现时以包内导出为准）

- **Slash**：使用 `@blocknote/react` 文档推荐的 **`SuggestionMenuController`**，`triggerCharacter="/"`，`getItems` 基于 **`getDefaultReactSlashMenuItems`**（或当前版本导出的等价符号，如 bundle 中的 `getDefaultReactSlashMenuItems`）生成默认项，必要时对返回列表做 `filter` 以隐藏与产品冲突的块类型（若无需则原样使用）。
- **侧栏（+ / 拖拽）**：使用与 0.48 配套的 **SideMenu** 相关导出（如 `SideMenu` 根组件、`SideMenuExtension` 等，以实现时 `grep` / 类型提示为准），确保与 `useCreateBlockNote` 返回的 `editor` 同一上下文内渲染。
- 若官方推荐将部分 UI 放在 `BlockNoteView` 非 Raw 包装内，实现阶段对比 **Raw + 子组件** 与 **完整 View** 的样式侵入；**优先** Raw + 碎片以控制仅描述区生效。

## 4. 与 `@` 提及的共存

- **`/`** 与 **`@`** 各用独立 `SuggestionMenuController`，不同 `triggerCharacter`。
- 手测：`/` → `@`、 `@` → `/`、Esc 关闭、键盘上下选择、失焦后自动保存仍正常；无重复打开或 z-index 遮挡（若有问题，用 CSS 变量或 Mantine 层级配置收敛）。

## 5. 样式与依赖

- 项目已全局引入 `@blocknote/mantine/style.css`；开启 block chrome 后可能增加侧栏/slash 的 Mantine 依赖类名，**接受**全局样式表；若出现与侧栏背景冲突，再在 `TaskEditor` / 包裹层 scoped 中做 `:deep` 微调（迭代项）。

## 6. 风险与测试

| 风险 | 缓解 |
|------|------|
| veaury + Portal 定位异常 | 在任务抽屉内全屏滚动、小视口下回归 |
| slash 与 @ 菜单层级 | 对比 z-index，必要时统一父级 `stacking context` |
| bundle 体积 | 仅多挂载组件树；若 tree-shaking 后仍明显增大，再评估是否动态 import（后续） |

**测试清单（手动）**

1. 描述区：输入 `/` 插入标题、列表、代码块等；点击 `+`；拖拽调整块序。  
2. 描述区：`@` 提及仍可用。  
3. 评论区 / 新建 Issue：确认无 slash 侧栏。  
4. 自动保存与刷新后内容一致。

## 7. 实现范围（文件级预期）

- `BlockNoteEditorWrapper.vue`：新增可选 prop，传入 React。  
- `BlockNoteEditorReact.tsx`：按 `blockChrome` 条件渲染 slash + SideMenu 等。  
- `TaskEditor.vue`：描述区实例打开 `blockChrome`。

不在本设计范围：格式化固定工具栏、Notion 级数据库块、自定义 slash 业务命令（可后续单独立项）。

## 8. 审批记录

- **2026-04-16**：产品/技术方案已获批准（对话确认「批准」），可进入实现计划与开发。
