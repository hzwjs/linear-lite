# 任务新增功能 UI/UX 体验评测报告

**评测范围**：任务（Issue）新增的入口、弹层（IssueComposer）、与看板/列表的衔接。  
**标准**：动线清晰、反馈及时、可访问性、视觉层级、容错与一致性。

---

## 一、入口与发现性

### 已具备
- **顶栏主按钮**「New issue」：位置明确，主操作突出。
- **命令栏「+」**：与 All issues / Active / Backlog 同排，符合「在视图中快速新增」预期。
- **看板列头「+」**：按状态分组时，在对应列创建并预填 `defaultStatus`，心智模型正确。
- **列表分组头「+」**：列表视图下按组创建，行为与看板一致。
- **快捷键 C**：非输入焦点时触发新建，适合键盘用户。
- **Command Palette（⌘K）**：「New task」可搜索，与其它全局命令统一。

### 问题与建议
1. **入口用语不统一**：界面多为 "New issue"，Command Palette 为 "New task"。建议统一为 "New issue" 或产品既定术语，并在 palette 的 keywords 中同时保留 `issue` / `task` 以便搜索。
2. **空状态**：「Create your first task」与主流程的 "New issue" 用词不一致，建议统一。
3. **列表行内「+」**：`TaskListView` 中每行 hover 的「Add sub-issue」按钮 `@click.stop` 未绑定逻辑，目前无效果，易造成「可点但无反馈」的困惑；要么接上子任务创建，要么在未实现时隐藏或禁用并加 tooltip 说明。

---

## 二、IssueComposer 弹层

### 布局与层级
- **Teleport to body** + 固定定位，层级清晰；`z-index: 220` 与 overlay 栈一致。
- **遮罩**：`rgba(17, 24, 39, 0.08)` 偏轻，在浅色背景下关闭与内容区的分隔感略弱；可考虑 0.12–0.16 或极轻的 `backdrop-filter`，便于聚焦弹层。
- **面板**：`min(720px, 100%)`、`padding: 8vh 24px 24px`，居中偏上，避免被键盘遮挡，合理。

### 标题与表单
- **标题**：「New issue」与主按钮一致。
- **标题输入**：`autofocus`、Enter 进描述区，焦点链正确。
- **描述区**：Tiptap + placeholder "Add description… Type / for formatting"，与 TaskEditor 一致，学习成本低。
- **属性区**：Status / Priority / Assignee / Due date 四个 pill 形控件横排，视觉统一；`min-width: 122px` 在小屏可能换行，但已有 `flex-wrap`，可接受。

### 问题与建议
1. **标题控件不一致**：Composer 用 `<input type="text">`，TaskEditor 用 `<textarea rows="2">`。多行标题在详情里可编辑，在创建时不可，建议统一为同一控件或明确设计理由（如创建仅单行）。
2. **无项目/上下文提示**：创建时未展示当前项目（如「在 Project X 下创建」），`projectStore.activeProjectId` 未传入 Composer；若 API 需要 `projectId`，当前可能依赖后端默认，建议在弹层内展示当前项目名或选择器，避免「不知道创建到哪」。
3. **Attach / Link / Watchers**：三个按钮为占位，无功能。建议：未实现时用 `disabled` + `title` 说明「即将推出」，或暂时移除，避免看起来可点却无反应。
4. **Create more**：勾选后创建成功仅重置表单、不关弹层，逻辑正确；但「Create more」文案对非英语用户略晦涩，可改为「继续创建下一个」或保留英文并加 tooltip。
5. **提交态**：`isSaving` 时按钮为 "Creating..." 且 disabled，防重复提交；缺少全局 loading 或遮罩，快速网络下可接受，慢网下可在弹层上叠加半透明 + 简单 spinner。
6. **错误反馈**：`handleCreate` 里 `catch` 仅 `console.error`，用户无 toast/inline 错误提示。建议至少 toast「创建失败，请重试」并保留表单内容。
7. **关闭方式**：点击遮罩或关闭按钮会 `emit('close')`；Esc 通过 overlay 栈统一关闭，行为正确。建议关闭前若表单有内容（标题或描述非空）做一次 confirm，减少误关导致丢失内容。

---

## 三、与 TaskEditor（详情）的一致性

- **字段对应**：Composer 与 TaskEditor 的 Status / Priority / Assignee / Due date 选项一致，创建后打开详情无「换了一套选项」的割裂感。
- **创建后动线**：`@created` → `handleCreated(taskId)` → `openWorkspace` + `router.push(/tasks/:taskId)`，即创建后自动打开该任务详情，符合「创建即编辑」的预期。
- **defaultStatus**：从列头/分组头传入，Composer 内 `status` 初始化为该值，列表/看板按状态分组时行为一致。

### 问题
- **TaskEditor 的 create 模式**：BoardView 中仅使用 `IssueComposer` 做创建，未使用 `TaskEditor mode="create"`。TaskEditor 的 create 模式仅有表单 UI，无「Create issue」提交按钮与提交逻辑，若未来要支持「在右侧抽屉内直接创建」，需要补提交逻辑与按钮；当前代码下 create 模式实为未完成路径，建议在需求明确前从 UI 上隐藏或移除 create 模式入口，避免混淆。

---

## 四、可访问性（a11y）

- **Composer**：`role="dialog"`、`aria-modal="true"`、`aria-label="Create issue"`，关闭按钮 `aria-label="Close"`。
- **CustomSelect**：传入了 `aria-label`（如 Status、Priority）。
- **焦点**：打开时 autofocus 在标题输入框；关闭后焦点未显式回到触发按钮，建议在 `closeComposer` 后 `focus` 到触发「New issue」的按钮，便于键盘用户连续操作。
- **键盘**：Enter 从标题到描述、Select 支持方向键与 Enter/Esc，无硬伤。Composer 内未监听 Esc 关闭（由 overlay 统一处理），需确认 overlay 的 Esc 能正确关闭 Composer 并返回焦点。

---

## 五、视觉与设计令牌

- **Design tokens**：`style.css` 中灰阶、主色（slate）、状态色、圆角、过渡时间统一，Composer 与全局一致。
- **主按钮**：`composer-submit` 使用 `var(--color-accent)`，与顶栏「New issue」一致，主操作识别度好。
- **Composer 标题区**：`.composer-title` 为 13px、次要色，与内容区标题字重 1.5rem 形成层级，合理。
- **小屏**：`@media (max-width: 720px)` 下 padding 与字号有调整，面板宽度 100%，基本可用；若属性区换行过多，可考虑横滑或折叠为「更多属性」。

---

## 六、总结与优先级建议

| 优先级 | 项 | 说明 |
|--------|----|------|
| 高 | 创建失败无用户可见反馈 | 增加 toast 或 inline 错误，并保留表单 |
| 高 | 关闭 Composer 前未确认 | 有内容时 confirm，防止误关丢内容 |
| 中 | 入口/空状态用语统一 | "issue" vs "task"、"first task" 与 "New issue" 统一 |
| 中 | 当前项目在创建时的展示 | 在 Composer 中显示或选择项目，避免「创建到哪」不明确 |
| 中 | 列表行「Add sub-issue」无行为 | 实现或隐藏/禁用并说明 |
| 低 | Attach/Link/Watchers 占位 | 禁用或移除并说明 |
| 低 | 关闭 Composer 后焦点回归 | 焦点回到触发按钮，便于键盘流 |
| 低 | 创建时标题单行 vs 详情多行 | 统一控件或明确设计说明 |

当前任务新增的主流程（入口 → 弹层 → 字段 → 提交 → 跳转详情）清晰可用；上述改进主要提升容错、一致性和可访问性，不影响主路径完成创建。
