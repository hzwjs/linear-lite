# Linear 风格任务描述编辑器 — 设计说明

**日期**：2025-03-10

**目标**：用 Tiptap 块编辑器替代 Vditor，实现「输入 `/` 弹出块菜单」+ 常用块（标题、列表、代码块、引用），无顶部工具栏；描述仍为单条 Markdown 字符串，后端与 API 不变。

---

## 1. 架构与数据流

- **职责边界**：任务描述仍由 `TaskEditor` 用 `formDescription`（string）管理；保存时继续调现有 `store.updateTask(..., { description })`，后端与 API 不改。
- **新编辑器组件**：替代当前 `VditorEditor`，接口保持一致：`props: modelValue: string, placeholder?, minHeight?`，`emit: update:modelValue(value: string)`。`TaskEditor` 仅替换子组件，其余逻辑（防抖保存、`descriptionForSave`、脏数据对比等）不动。
- **内部数据流**：挂载时用 `modelValue`（Markdown）初始化 Tiptap；用户编辑时按现有策略（实时或失焦）将内容序列化为 Markdown 并 `emit('update:modelValue', md)`；持久化到后端的始终是一条 Markdown 字符串。

## 2. 组件与依赖

- **组件**：新增 `TiptapEditor.vue`，在 `TaskEditor.vue` 中替换对 `VditorEditor` 的引用。**VditorEditor.vue 不保留**，实现完成后删除并移除 `vditor` 依赖。
- **依赖**：Tiptap 生态，例如：
  - `@tiptap/core`、`@tiptap/vue-3`
  - `@tiptap/starter-kit`（Paragraph、Heading、Bold、Italic 等，按需关闭）
  - `@tiptap/extension-code-block`、`@tiptap/extension-blockquote`（若 starter-kit 未含则单独配置）
  - 列表与任务列表：`@tiptap/extension-bullet-list`、`@tiptap/extension-ordered-list`、`@tiptap/extension-task-list`、`@tiptap/extension-task-item`
  - Markdown 双向：如 `tiptap-markdown` 或配套 Markdown 序列化方案
- **UI**：不渲染顶部工具栏；仅通过 `/` 触发的块菜单插入块，行内格式依赖快捷键（如 ⌘B、⌘I）。

## 3. Slash 菜单与块类型

- **触发**：输入 `/` 时在光标位置弹出浮层菜单；`/` 不写入文档。
- **菜单项**：Heading 1 / 2 / 3，Bulleted list、Numbered list、Checklist，Code block，Blockquote；可选带图标或简短说明。
- **行为**：选中后插入对应块、焦点进入新块、菜单关闭；Esc 或点击外部关闭菜单并去掉触发用的 `/`。
- **首版**：固定列表即可，`/` 后输入过滤可选后续再做。

## 4. Markdown 序列化与往返

- **加载**：用选定的 Markdown 扩展将 `description` 转成 Tiptap 文档并 `setContent`；解析失败或为空则设为空文档（单段空段落）。
- **保存**：将当前 Tiptap 文档序列化为一条 Markdown 字符串，经 `update:modelValue` 交给父组件写入后端。
- **往返**：优先保证标题、无序/有序/Checklist、代码块、引用在「打开 → 编辑 → 保存 → 再打开」下一致；其它格式尽量保留，边界 case 可回退为纯文本。

## 5. 错误与边界

- **空或非法 description**：按空文档初始化，不向父组件抛错。
- **序列化失败**：保存时若 Tiptap → Markdown 异常，回退为当前文档的纯文本再序列化，避免静默丢内容。
- **未保存离开**：沿用现有 TaskEditor 行为，本阶段不新增逻辑。
- **样式与无障碍**：使用现有 CSS 变量；块菜单支持键盘聚焦与 Esc 关闭，基本 a11y。

## 6. 测试与验收

- **手工**：打开任务 → `/` 弹出菜单 → 插入各块类型 → 输入内容 → 保存 → 再打开确认格式与内容。
- **自动化（可选）**：对 Markdown 往返（标题、列表、代码块、引用）做单元测试。
- **完成标准**：TaskEditor 使用新块编辑器；无 Vditor 引用；`vditor` 从 package.json 移除；描述读写与保存流程不变。
