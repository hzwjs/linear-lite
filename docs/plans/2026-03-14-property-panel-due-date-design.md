# 任务详情右侧属性面板优化 — 设计说明

**日期**: 2026-03-14  
**范围**: Due Date 日历显示不全修复 + 属性面板布局规范

---

## 1. 问题与目标

- **现象**: 任务详情页右侧属性栏（220px）内，Due Date 日历层 `min-width: 240px` 且 `left: 0` 对齐触发器，右侧被 `.editor-panel` 的 `overflow: hidden` 裁切，需右滑才能看全（周六、周日列等）。
- **目标**:  
  - 先修 Due Date：日历在任何容器下都完整可见、无需横向滑动。  
  - 再为属性面板定布局规范，避免后续控件同样溢出。

---

## 2. Part 1：Due Date 日历（CustomDatePicker）

### 2.1 方案

采用 **Teleport + 视口内定位/翻转**：日历层挂到 `body`，用 JS 根据 trigger 的 `getBoundingClientRect()` 计算位置，右侧超出视口时改为向左展开（flip），必要时可向下空间不足时向上 flip。

### 2.2 实现要点

- **组件**: 仅修改 `src/components/ui/CustomDatePicker.vue`。
- **Teleport**: 日历面板用 `<Teleport to="body">` 渲染，保留现有 DOM 结构和 class，便于沿用现有样式。
- **定位逻辑**:
  - 打开时读取 `triggerRef.getBoundingClientRect()` 与面板尺寸（或固定 min-width 240px）。
  - 默认：在 trigger 正下方、左对齐（与当前一致）。
  - 若 `rect.right + panelWidth > window.innerWidth`：改为右对齐 trigger（面板向左展开）。
  - 可选：若 `rect.bottom + panelHeight > window.innerHeight`：改为在 trigger 上方展开。
- **尺寸**: 面板保持 `min-width: 240px`，不缩小日历格子。
- **resize/scroll**: 打开状态下监听 `window` 的 `resize` 与必要的 `scroll`（可 throttle），更新面板位置；关闭时移除监听。
- **无障碍**: 保持现有 `role="dialog"`、焦点与 Esc 关闭；Teleport 后焦点仍落在文档流内，无需改焦点逻辑。

### 2.3 验收

- 在任务详情右侧属性栏打开 Due Date，日历完整可见（含 Mon–Sun 与全部日期格），无需横向滚动。
- 在窄视口或右侧空间不足时，日历自动向左展开，不超出视口。
- 其他使用 CustomDatePicker 的场景（如 IssueComposer）行为不变或同样受益。

---

## 3. Part 2：属性面板布局规范

### 3.1 方案

- **实现**: 将 `.editor-props` 由固定 `width: 220px` 改为 `min-width: 260px`（或 280px），保证当前所有下拉/日历在侧栏内展开有基本空间。若与现有 `@media (max-width: 1100px)` 断点冲突，则在该断点内保持「宽度 auto + 纵向堆叠」即可。
- **文档**: 在开发/设计文档中增加一条：属性面板内新增控件若弹出层较宽，应使用 Teleport 或限制宽度，避免被父级 overflow 裁切；为后续统一弹出层行为（如全面 Teleport）留口子。

### 3.2 实现要点

- **文件**: `src/components/TaskEditor.vue`，仅调整 `.editor-props` 的 width 为 min-width。
- **文档**: 可在本设计文档或 `docs/` 下的前端/组件规范中追加「属性面板布局与弹出层」小节，注明上述规则。

### 3.3 验收

- 属性栏在 ≥1100px 视口下为至少 260px 宽，现有 Status/Priority/Assignee/Due Date 等控件正常显示与展开。
- 断点以下仍为纵向堆叠，无横向挤压或错位。

---

## 4. 依赖与风险

- 无新增依赖；Vue 3 已提供 `Teleport`。
- 风险：Teleport 后若存在固定定位的祖先或 transform，可能影响层叠；当前编辑器无全屏 fixed 遮罩，可忽略。若将来引入，再评估使用统一 portal 根节点。

---

## 5. 后续可选

- 若后续在属性面板增加更多宽弹出层，可统一将 CustomSelect 等也改为 Teleport + 定位，与 CustomDatePicker 一致。
