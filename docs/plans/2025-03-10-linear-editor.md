# Linear 风格任务描述编辑器 — 实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 用 Tiptap 块编辑器替代 Vditor，实现输入 `/` 弹出块菜单（标题、列表、代码块、引用），无工具栏；描述仍为单条 Markdown 字符串，后端不变。

**Architecture:** 新增 `TiptapEditor.vue`，接口与现 `VditorEditor` 一致（modelValue/update:modelValue）；内部用 Tiptap + Markdown 扩展做双向序列化；TaskEditor 仅替换子组件并删除 Vditor 相关代码与依赖。

**Tech Stack:** Vue 3, Tiptap (@tiptap/core, @tiptap/vue-3, @tiptap/starter-kit, 各块扩展), Markdown 解析/序列化（见 Task 2 选型）, Vitest（可选单测）.

**设计文档:** `docs/plans/2025-03-10-linear-editor-design.md`

---

### Task 1: 添加 Tiptap 依赖并创建 TiptapEditor 骨架

**Files:**
- Modify: `package.json`
- Create: `src/components/TiptapEditor.vue`

**Step 1: 添加依赖**

在 `package.json` 的 `dependencies` 中增加：

```json
"@tiptap/core": "^2.10.0",
"@tiptap/vue-3": "^2.10.0",
"@tiptap/starter-kit": "^2.10.0",
"@tiptap/extension-code-block": "^2.10.0",
"@tiptap/extension-blockquote": "^2.10.0",
"@tiptap/extension-bullet-list": "^2.10.0",
"@tiptap/extension-ordered-list": "^2.10.0",
"@tiptap/extension-list-item": "^2.10.0",
"@tiptap/extension-task-list": "^2.10.0",
"@tiptap/extension-task-item": "^2.10.0",
"@tiptap/extension-heading": "^2.10.0"
```

执行: `npm install`

**Step 2: 创建 TiptapEditor.vue 骨架**

创建 `src/components/TiptapEditor.vue`：使用 `useEditor` + `EditorContent`，仅启用 Document、Paragraph；props：`modelValue`(string)、`placeholder`(string)、`minHeight`(number)；初始化时用 `editor.commands.setContent(props.modelValue || '<p></p>')`（先按 HTML 传入，后续 Task 再接 Markdown）；`onUpdate` 中 `emit('update:modelValue', editor.getHTML())`；无工具栏。占位符可用 CSS 或 Tiptap 的 placeholder 扩展（可选）。模板仅 `<EditorContent />` 包一层 div，class 如 `tiptap-editor-wrap`。

**Step 3: 验证**

运行 `npm run build`，应通过。不修改 TaskEditor 时仅新增文件与依赖，无行为变化。

**Step 4: Commit**

```bash
git add package.json package-lock.json src/components/TiptapEditor.vue
git commit -m "chore(editor): 添加 Tiptap 依赖与 TiptapEditor 骨架"
```

---

### Task 2: 接入 Markdown 解析与序列化

**Files:**
- Create: `src/utils/editorMarkdown.ts`
- Modify: `src/components/TiptapEditor.vue`

**选型说明:** 使用现有 `marked` 将 Markdown → HTML 供 Tiptap 解析；Tiptap → Markdown 使用 `turndown`（需新增依赖）将 `editor.getHTML()` 转回 Markdown，或使用 `tiptap-markdown` 等库（若更符合块结构）。以下按 marked + turndown 方案写；若改用 tiptap-markdown，则替换为对应 API。

**Step 1: 添加 turndown**

`package.json` 的 dependencies 增加 `"turndown": "^7.2.0"`，并 `npm install`。可选：添加 `@types/turndown` 到 devDependencies（若存在）。

**Step 2: 实现 editorMarkdown.ts**

创建 `src/utils/editorMarkdown.ts`：
- `mdToHtml(md: string): string`：若 `md` 为空或仅空白，返回 `'<p></p>'`；否则用 `marked.parse(md)` 得到 HTML 并返回（注意 marked 已存在，保持与 `src/utils/markdown.ts` 的用法一致或复用）。
- `htmlToMd(html: string): string`：用 Turndown 将 html 转为 Markdown 字符串；配置保留 code block、heading、list、blockquote 等；若 html 为空或仅 `<p></p>`，返回 `''`。

**Step 3: TiptapEditor 使用 Markdown**

在 `TiptapEditor.vue` 中：初始化时 `setContent(mdToHtml(props.modelValue))`；`onUpdate` 时 `emit('update:modelValue', htmlToMd(editor.getHTML()))`。watch `props.modelValue`，当外部变化且与当前内容不一致时 `setContent(mdToHtml(newVal))`，避免覆盖用户正在编辑的内容（可与现有 VditorEditor 的 watch 逻辑对齐：仅当非用户触发的更新才同步）。

**Step 4: 验证**

运行 `npm run build`。手工或后续任务中验证：空字符串、简单段落、`## 标题`、`- a`、` ```\ncode\n``` `、`> 引用` 往返一次无丢失。

**Step 5: Commit**

```bash
git add package.json package-lock.json src/utils/editorMarkdown.ts src/components/TiptapEditor.vue
git commit -m "feat(editor): Markdown 与 Tiptap HTML 双向转换"
```

---

### Task 3: 启用块扩展并关闭 StarterKit 中不需要的项

**Files:**
- Modify: `src/components/TiptapEditor.vue`

**Step 1: 配置扩展**

在 TiptapEditor 的 `useEditor` 的 `extensions` 中：
- 使用 `StarterKit` 时通过配置关闭 `CodeBlock`（用独立 `CodeBlock` 扩展以便后续 slash 插入）。
- 显式加入：`Heading`（levels [1,2,3]）、`BulletList`、`ListItem`、`OrderedList`、`TaskList`、`TaskItem`、`CodeBlock`、`Blockquote`。
- 确保不渲染工具栏（不添加任何 Toolbar 相关扩展）。

**Step 2: 验证**

`npm run build` 通过。打开任务详情，描述区域可输入并保存；列表、标题等可输入（slash 菜单在 Task 4 做）。

**Step 3: Commit**

```bash
git add src/components/TiptapEditor.vue
git commit -m "feat(editor): 启用标题/列表/代码块/引用扩展"
```

---

### Task 4: 实现 Slash 块菜单

**Files:**
- Create: `src/components/TiptapSlashMenu.vue`（或内联在 TiptapEditor 中，二选一）
- Modify: `src/components/TiptapEditor.vue`

**Step 1: 监听 `/` 并弹出菜单**

在 TiptapEditor 中：监听编辑器输入，当用户输入 `/` 时，阻止默认并显示块选择菜单（浮层）。浮层位置基于当前选区/光标（可用 `editor.view.coordsAtPos` 等）。菜单项：Heading 1、Heading 2、Heading 3、Bulleted list、Numbered list、Checklist、Code block、Blockquote。

**Step 2: 选择后插入块**

选中某项后执行对应命令，例如：
- Heading 1/2/3：`editor.chain().focus().setHeading({ level: 1|2|3 }).run()`
- Bulleted list：`toggleBulletList()`
- Numbered list：`toggleOrderedList()`
- Checklist：`toggleTaskList()` 或插入 taskList + taskItem
- Code block：`setCodeBlock()`
- Blockquote：`toggleBlockquote()`

插入后关闭菜单，焦点留在新块。若用户按 Esc 或点击外部，关闭菜单并删除触发用的 `/`（即回退一个字符）。

**Step 3: 样式与 a11y**

菜单使用现有 CSS 变量；支持键盘上下选择、Enter 确认、Esc 关闭；浮层带 `role="menu"` 或 `listbox`、`aria-*` 等。

**Step 4: 验证**

手工：在描述中输入 `/`，菜单出现；选择各项，对应块插入正确；Esc 关闭且 `/` 被删除。

**Step 5: Commit**

```bash
git add src/components/TiptapEditor.vue src/components/TiptapSlashMenu.vue
git commit -m "feat(editor): 输入 / 弹出块菜单并插入对应块"
```

---

### Task 5: TaskEditor 接入 TiptapEditor 并移除 Vditor

**Files:**
- Modify: `src/components/TaskEditor.vue`
- Delete: `src/components/VditorEditor.vue`
- Modify: `package.json`

**Step 1: 替换引用**

在 `TaskEditor.vue` 中：将 `import VditorEditor from './VditorEditor.vue'` 改为 `import TiptapEditor from './TiptapEditor.vue'`；将模板中的 `<VditorEditor ... />` 改为 `<TiptapEditor ... />`，props 保持 `v-model="formDescription"`、`placeholder`、`:min-height="160"`；若原有多余的 `hide-toolbar` 等可去掉（TiptapEditor 无工具栏）。

**Step 2: 删除 Vditor**

删除 `src/components/VditorEditor.vue`。在 `package.json` 的 `dependencies` 中移除 `"vditor": "^3.11.2"`。执行 `npm install`。

**Step 3: 描述展示兼容**

若项目其它处有直接用 `description` 做富文本展示（如 `renderMarkdown(description)`），保持不动——后端仍存 Markdown，TiptapEditor 输出 Markdown，现有 `markdown.ts` 的 `renderMarkdown` 仍可用于只读展示。

**Step 4: 验证**

`npm run build` 通过。打开看板 → 新建/编辑任务 → 描述区为 Tiptap 编辑器，输入 `/` 出现菜单，插入块后保存，再打开任务描述正确；无 Vditor 引用与运行时报错。

**Step 5: Commit**

```bash
git add src/components/TaskEditor.vue package.json package-lock.json
git rm src/components/VditorEditor.vue
git commit -m "feat(editor): 用 TiptapEditor 替代 Vditor，移除 vditor 依赖"
```

---

### Task 6: 样式与边界情况

**Files:**
- Modify: `src/components/TiptapEditor.vue`（及 Slash 菜单相关样式）

**Step 1: 样式统一**

Tiptap 内容区与菜单使用 `--color-text-primary`、`--font-size-body`、`--color-bg-subtle` 等现有变量；编辑器容器与 `.description-section` 等现有布局一致；`.tiptap-editor-wrap` 最小高度来自 `minHeight` prop。

**Step 2: 边界**

空或非法 `modelValue`：在 `mdToHtml` 或 setContent 前处理，空字符串或解析异常时设为 `'<p></p>'`。序列化失败：在 `htmlToMd` 异常时回退为纯文本（例如从 HTML 抽 textContent 再作为单段 Markdown 返回），避免静默丢内容。

**Step 3: 验证**

深色/浅色主题下编辑器与菜单可读；空描述、纯空格描述打开不报错；保存后刷新再打开内容一致。

**Step 4: Commit**

```bash
git add src/components/TiptapEditor.vue src/components/TiptapSlashMenu.vue
git commit -m "style(editor): 统一主题变量与边界处理"
```

---

### Task 7（可选）: Markdown 往返单测

**Files:**
- Create: `src/utils/editorMarkdown.test.ts`
- Modify: 无（仅测试）

**Step 1: 写测试**

对 `editorMarkdown.ts` 的 `mdToHtml` 与 `htmlToMd` 做往返测试：至少覆盖「空字符串」「简单段落」「## 标题」「- 项」「1. 项」「- [ ] 待办」「```\ncode\n```」「> 引用」各一例，即 `expect(htmlToMd(mdToHtml(md))).toBe(md)` 或等价断言（允许空白/换行规格化）。

**Step 2: 运行**

`npx vitest run src/utils/editorMarkdown.test.ts`，应全部通过。

**Step 3: Commit**

```bash
git add src/utils/editorMarkdown.test.ts
git commit -m "test(editor): Markdown 往返序列化单测"
```

---

## 执行选项

计划已保存至 `docs/plans/2025-03-10-linear-editor.md`。

**两种执行方式：**

1. **Subagent-Driven（本会话）** — 按任务拆给子 agent，每步完成后你做代码审查与验收，再进入下一任务。
2. **独立会话并行执行** — 在新会话（建议在专用 worktree）中打开本计划，使用 superpowers:executing-plans 按任务顺序执行，并在检查点做批量审查。

选哪一种？
