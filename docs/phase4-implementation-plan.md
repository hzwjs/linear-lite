# Linear Lite - Phase 4 实施计划（UI/UX 极致优化）

基于 `prd/phase4-ideas.md` 与 `phase4-linear-gap-analysis.md` 确定的演进目标：不新增业务模型，全力翻新前端呈现，像素级对齐 Linear.app 的质感、排版与微交互。Phase 4 基础（Sprint 1–3）已完成；Phase 4.1 补齐与 Linear 的布局与视觉差距。

## 目标摘要

1. **视觉基础（已完成）**：全局 CSS 变量、Inter、Lucide，全站 CustomSelect/CustomDatePicker，无原生 select/date；List 行高 40–44px、Pills 化、Hover 显隐；Command Palette 与 C/Esc。
2. **布局（更正）**：Issue 视图**不采用**主区常驻两栏；保持 **overlay + 右侧滑出 Drawer**（与用户确认：Linear 并非主区两栏设计）。看板列用 **1px 线分栏**、主区统一画布、列头「+」、去卡片化、模态框减轻等已实施并保留。
3. **线条化与去卡片化（Phase 4.1 P1）**：列头「+」新建；看板卡片与列表分组减圆角/阴影，用线+留白区分；模态框减轻浮层感（P2）。

---

## Phase 4 已完成（Sprint 1–3）

- 全局 Variables、Inter、Lucide
- CustomSelect、CustomDatePicker，全站替换 select/date
- List Pills 化、Hover 显隐、分组保留
- TaskEditor 两栏 Drawer（左标题+描述，右属性板）、轻遮罩
- Board 与 List 共用变量、150ms 过渡、自定义滚动条
- Command Palette (⌘K)、C、Esc

---

## Phase 4.1 Proposed Changes（基于 gap-analysis）

### 1. Issue 视图形态（已更正，不采用主区两栏）

**更正**：此前将「主区两栏（左 List/Board | 右 Issue Details）」当作 Linear 设计并写入 plan，属误判；**Linear 并非这样的设计**。  
**当前**：保持 **overlay + 右侧滑出 Drawer**；列头「+」、线分栏、去卡片化、模态框减轻等 Phase 4.1 其余项已实施并保留。

### 2. 看板线分栏与去卡片化（P0）

**现状**：`.column` 为独立圆角卡片（border + border-radius + 背景），列间 gap 大，视觉像「多张卡片摆在灰底上」。

**目标**：主区为**统一画布**，列与列之间用 **1px 竖线（border-left）** 分割；列头与卡片区用 **1px 横线** 分割；去掉列的独立圆角与厚重阴影。

**实现要点**：
- `.board-columns` 背景统一（如 `var(--color-bg-main)`），列之间用 `border-left: 1px solid var(--color-border)` 分割，去掉 `gap` 或改用 0 gap + 线分隔。
- `.column` 去掉 `border-radius`、`border` 全边框，仅保留 `border-left`（首列无）或作为分隔线容器。
- 列头 `.column-header` 与 `.column-list` 之间加 `border-bottom: 1px solid var(--color-border)`；列头与内容同一背景。

### 3. 列头「+」新建（P1）

**现状**：仅顶部「New Issue」+ 快捷键 C。

**目标**：每列列头增加 **「+」** 按钮，点击后在该列顶部插入新任务，并聚焦到右侧详情（或 inline 行）。新建完成后右侧即显示新任务。

**实现要点**：
- 列头右侧增加「+」按钮；点击时 `openCreateEditor()` 并传入 `defaultStatus: col.id`，创建后 task 进入该列。
- 与两栏布局联动：新建后 `currentTaskId = 新任务 id`，右栏显示该任务。

### 4. 看板卡片与列表分组去卡片化（P1）

**看板卡片**：
- TaskCard：减小圆角（如 4px 或 2px），阴影极轻或去掉；边框用 1px 细线；整体像「板上的条」而非「浮起来的块」。

**列表分组**：
- `.group`：去掉整块背景+圆角，改用 **线+字** 区分——分组头下加 `border-bottom: 1px solid var(--color-border)`，分组头背景与主区一致或仅差一档。

### 5. 模态框减轻浮层感（P2）

- CreateProjectModal、ProjectSettingsModal：减轻 `box-shadow`，缩小 `border-radius`；或改为从侧边滑入的面板形态，与主布局衔接。

---

## 验证计划

### Phase 4.1 人工验证

1. **两栏布局**：选中/新建 Issue 时，右侧显示详情，左侧 List/Board 始终可见、无遮罩；无选中时右栏有占位。
2. **看板线分栏**：列之间为 1px 竖线，列头与卡片区为横线；无独立圆角卡片列。
3. **列头「+」**：每列列头可点击「+」新建，新任务出现在该列，右侧显示新任务。
4. **去卡片化**：看板卡片与列表分组视觉更扁、更「线条切分」，无强烈浮层感。
5. **模态框**：阴影与圆角减轻，与整体风格一致。

---

## 与 Phase 3 的衔接

- 无后端变更；API、Store 与 Phase 3 保持一致。
- Phase 4.1 主要为布局与样式调整：BoardView 结构、TaskEditor 挂载方式、Board 列样式、CreateProjectModal/ProjectSettingsModal 样式。
