# Phase 4 任务清单（UI/UX 极致优化）

基于 `prd/phase4-ideas.md`、`phase4-implementation-plan.md`、`phase4-linear-gap-analysis.md` 拆解。  
Phase 4 基础（Sprint 1–3）已完成；Phase 4.1 补齐与 Linear 的布局与视觉差距（P0/P1）。

**图例**：`[ ]` 未开始 | `[x]` 已完成 | 依赖用 `←` 标注。

---

## 0. 前置（沿用 Phase 3，无新增决策）

Phase 3 技术栈与前后端接口已确认；Phase 4 仅做前端呈现与交互改造，无 API 与数据模型变更。

---

## 1. Sprint 1：全局样式与设计令牌（已完成）

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P4-1.1 | 全局 CSS 变量：浅色主题 (--color-bg-main, sidebar, text-primary/secondary, border, hover, 状态色) | [x] | 无 |
| P4-1.2 | 引入 Inter 字体，设置 body font-family 与 fallback | [x] | 无 |
| P4-1.3 | 全局 -webkit-font-smoothing: antialiased；主体 14px、辅助 12px，letter-spacing -0.01em | [x] | P4-1.1 |
| P4-1.4 | 引入 Lucide 图标库，约定 16px/20px 两档 | [x] | 无 |

---

## 2. Sprint 1：底层控件组件（已完成）

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P4-2.1 | 实现 CustomSelect/Popover：选项前置 Icon、方向键/Enter/Esc、Tab 获焦、ARIA | [x] | P4-1.1, P4-1.4 |
| P4-2.2 | TaskEditor：状态/优先级/负责人下拉全部替换为 CustomSelect | [x] | P4-2.1 |
| P4-2.3 | BoardView/ListView 筛选器替换为 CustomSelect | [x] | P4-2.1 |
| P4-2.4 | 实现 CustomDatePicker，视觉与 CustomSelect 统一 | [x] | P4-2.1, P4-1.1 |
| P4-2.5 | TaskEditor：Due Date 替换为 CustomDatePicker | [x] | P4-2.4 |

---

## 3. Sprint 1：视觉收紧预备（已完成）

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P4-3.1 | ListView 行高锁定 40–44px，应用 14/12px 与 CSS 变量 | [x] | P4-1.1, P4-1.3 |
| P4-3.2 | 全局按钮、输入内边距向新规范收紧 | [x] | P4-1.1 |

---

## 4. Sprint 2：List 进化与 Pills 化（已完成）

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P4-4.1 | ListView 左侧：Hover 显隐（优先级图标 ↔ 勾选圈） | [x] | P4-2.1, P4-3.1 |
| P4-4.2 | ListView 右侧：状态、负责人(24px)、截止日改为 Pills 胶囊布局 | [x] | P4-4.1 |
| P4-4.3 | 保留按状态/优先级分组与折叠，视觉与布局适配 | [x] | P4-4.2 |

---

## 5. Sprint 2：Task 视图形态（Phase 4.1 P0 升级）

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P4-5.1 | ~~主区两栏~~（已更正：Linear 非此设计，已恢复 overlay+Drawer） | — | — |
| P4-5.2 | 右侧详情：大标题无边框 textarea (18–20px) + 描述 + 右栏裸按钮属性板 | [x] | P4-5.1 |
| P4-5.3 | 右侧详情：状态/优先级/负责人/截止日为裸按钮，呼出 CustomSelect/DatePicker | [x] | P4-5.1 |
| P4-5.4 | Drawer 使用指定 box-shadow，极淡 overlay（已恢复，非主区两栏） | [x] | P4-5.1 |

---

## 6. Sprint 2：Board 与微动效（Phase 4.1 升级）

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P4-6.1 | Board 泳道与卡片应用 CSS 变量、14/12px，与 List 风格一致 | [x] | P4-1.1, P4-3.1 |
| P4-6.2 | Sidebar、列表行、卡片、视图切换增加 transition: 150ms ease | [x] | P4-1.1 |
| P4-6.3 | 自定义滚动条：收敛或 hover 显现 | [x] | P4-1.1 |
| P4-6.4 | **Phase 4.1 P0**：看板线分栏——列之间用 1px 竖线，去掉列独立圆角/厚重阴影；主区统一画布 | [x] | P4-6.1 |
| P4-6.5 | **Phase 4.1 P1**：每列列头增加「+」按钮，点击在该列顶部新建任务并聚焦右侧详情 | [x] | P4-5.1, P4-6.4 |
| P4-6.6 | **Phase 4.1 P1**：TaskCard 减圆角/阴影，视觉像「板上的条」 | [x] | P4-6.4 |

---

## 7. Sprint 2：List 分组去卡片化（Phase 4.1 P1）

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P4-4.4 | 列表分组：`.group` 去掉整块背景+圆角，改用线+字区分（分组头下 border-bottom） | [x] | P4-4.3 |

---

## 8. Sprint 3：Command Palette 与快捷键（已完成）

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P4-7.1 | 实现 Command Palette：⌘K 呼出、关键词过滤、焦点陷阱、Esc 关闭 | [x] | P4-5.1 |
| P4-7.2 | 命令项：新建任务、切换 Board/List、打开项目设置、聚焦搜索框 | [x] | P4-7.1 |
| P4-7.3 | 全局快捷键 C：唤起新建任务 | [x] | P4-7.1 |
| P4-7.4 | 全局快捷键 Esc：关闭最上层浮层 | [x] | P4-7.1 |

---

## 9. Phase 4.1 P2：模态框减轻浮层感

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P4-9.1 | CreateProjectModal、ProjectSettingsModal：减轻 box-shadow、缩小 border-radius，或改为滑入面板 | [x] | P4-1.1 |

---

## 10. 验证与收尾

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P4-8.1 | 视觉验收：Inter、变量色、14/12px、无原生 select/date，List 行高 40–44px | [x] | P4-3.x, P4-2.x |
| P4-8.2 | List 验收：Hover 显隐、Pills、分组、打开详情正常 | [x] | P4-4.x, P4-5.x |
| P4-8.3 | ~~两栏布局~~（已撤销；现为 overlay+Drawer） | — | — |
| P4-8.4 | Board 线分栏验收：列间竖线、列头横线、无独立圆角卡片列 | [x] | P4-6.4 |
| P4-8.5 | 列头「+」验收：每列可新建、新任务进该列、右侧显示 | [x] | P4-6.5 |
| P4-8.6 | 去卡片化验收：卡片与分组视觉扁化、线条切分 | [x] | P4-6.6, P4-4.4 |
| P4-8.7 | Command Palette 与快捷键验收 | [x] | P4-7.x |
| P4-8.8 | 无障碍抽查：CustomSelect/DatePicker/Palette 仅键盘可操作 | [ ] | P4-2.x, P4-7.x |

---

## 完成标准

### Phase 4 基础（已完成）
- [x] 全站 Phase 4 色彩变量、Inter、Lucide，无原生 select/date
- [x] List 高密度 40–44px、Hover 显隐、Pills；Task 编辑两栏（Drawer 形态）
- [x] Board 与 List 视觉统一，150ms 过渡
- [x] Command Palette (⌘K)、C、Esc 可用

### Phase 4.1（gap-analysis P0/P1）
- ~~Issue 视图主区两栏~~（已更正：Linear 非此设计，已恢复 overlay+Drawer）
- [x] 看板线分栏：列间 1px 竖线、列头横线，主区统一画布，无独立圆角卡片列
- [x] 每列列头「+」新建，新建后右侧显示
- [x] 看板卡片与列表分组去卡片化，线条切分
- [x] 模态框减轻浮层感（P2）

---

## 任务依赖简图

```
Phase 4 已完成: P4-1.x → P4-2.x → P4-3.x → P4-4.x → P4-5.x(部分) → P4-6.x(部分) → P4-7.x

Phase 4.1:
P4-5.1, P4-5.4 (主区两栏，去 overlay)
    → P4-6.4 (看板线分栏)
         → P4-6.5 (列头 +)、P4-6.6 (卡片扁化)
P4-4.4 (列表分组去卡片化) [独立]
P4-9.1 (模态框减轻) [独立]
         → P4-8.3, P4-8.4, P4-8.5, P4-8.6 (验收)
```
