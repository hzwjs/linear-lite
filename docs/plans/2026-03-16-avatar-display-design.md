# 头像显示优化 — 设计说明（方案 A）

**日期**: 2026-03-16  
**范围**: 无头像时「双字符 + 稳定背景色」占位，提升中英文混合场景下的区分度

---

## 1. 目标与背景

- **问题**: 当前无 `avatar_url` 时仅显示用户名首字符，同姓（如全为「黄」）时列表内难以区分。
- **目标**: 无头像时显示最多 2 个字符 + 按用户稳定的背景色，列表/卡片/活动流统一行为；有 `avatar_url` 时逻辑不变。

---

## 2. 字符规则（getInitials）

- **英文/拼音（含空格）**: 取「首单词首字母 + 次单词首字母」大写。  
  例：`John Doe` → `JD`，`Zhang San` → `ZS`。  
  仅一个单词：取前 2 个字符大写，不足 2 则 1 个。例：`Alice` → `AL`，`A` → `A`。
- **中文（CJK 或连续无空格）**: 取前 2 个字符；仅 1 字则 1 字。  
  例：`黄志文` → `黄志`，`张三` → `张三`，`李` → `李`。
- **判定**: 若字符串包含空格，按「英文/拼音」规则；否则按「前 N 个字符」（中文或单单词）规则。不区分语言编码，仅按空格与长度处理。
- **空/未分配**: `Unassigned` 或空名返回 `—`（与现有一致），不参与颜色。

---

## 3. 颜色算法（getAvatarColor）

- **输入**: `userId: number`（稳定且唯一）。
- **输出**: CSS 可用的背景色，以及保证对比度的前景色（白或深灰）。
- **算法**:  
  - 用 `userId` 做简单哈希（如 `((id * 2654435761) >>> 0) % 360`）得到色相 H。  
  - 固定 S=65%、L=45%（或 L=50%）得到 HSL，转成 hex 或 `hsl()` 作为背景。  
  - 前景：背景 L &lt; 0.5 用白色 `#fff`，否则用深灰 `#374151`，保证可读性。
- **色板**: 不预定义列表，按 id 连续分布色相，相邻用户颜色差异自然拉开。

---

## 4. 使用位置与组件

| 位置 | 文件 | 当前行为 | 变更 |
|------|------|----------|------|
| 任务列表（assignee 列） | `TaskListView.vue` | `assigneeInitial` 单字 + `.avatar-18.fallback` 灰底 | 用 `getInitials` + `getAvatarColor(userId)`，fallback 应用动态背景/前景 |
| 任务卡片（assignee 行） | `TaskCard.vue` | `assigneeInitial` 单字 + `.avatar` 灰底 | 同上 |
| 活动流头像 | `TaskEditor.vue` | `getActivityAvatarLabel` 单字 | 改为调用 `getInitials(actorName)`；若需颜色可用 `actorId` 或不用颜色（仅字符） |

活动流若后端未返回 `actorId`，可仅做双字符、不加颜色，避免为颜色再拉接口。

---

## 5. 工具函数接口

- **`getInitials(name: string): string`**  
  - 入参：`username` 或 `Unassigned`。  
  - 返回：最多 2 个字符（或 `—`）。  
  - 放置：`src/utils/avatar.ts`（新建），便于列表/卡片/活动共用。

- **`getAvatarColor(userId: number): { background: string; color: string }`**  
  - 入参：`User.id`。  
  - 返回：`{ background, color }` 用于内联样式或 CSS 变量。  
  - 放置：同上 `src/utils/avatar.ts`。

- **现有** `getActivityAvatarLabel`（`taskActivity.ts`）：改为内部调用 `getInitials`，或活动流直接使用 `getInitials(actorName)`，保持单一实现。

---

## 6. 样式与无障碍

- **小尺寸（如 18–20px）**: 双字符时字体保持 10px（或略小），字重 medium；若仍挤可微调 `letter-spacing` 或仅在小头像显示 1 字符（可选，优先保证 2 字符可读）。
- **对比度**: 前景/背景满足 WCAG AA（由 `getAvatarColor` 的 L 与前景选择保证）。
- **Tooltip**: 保留现有 `data-tooltip` / `title` 显示完整用户名。
- **alt/无障碍**: 有头像时 `alt` 仍为用户名；无头像时 fallback 的 `span` 可加 `aria-label={assigneeDisplay}`，或由外层 `title` 覆盖。

---

## 7. 验收

- 列表/卡片：无头像用户显示 2 字符（或 1 字）+ 按 userId 的稳定彩色背景，同一用户在不同任务中颜色一致。
- 中英文混合：`黄志文` → 黄志，`John Doe` → JD，`huangzhiwen` → HU（或 HZ，与实现约定一致）。
- 活动流：至少为双字符（若暂无 actorId 可不改颜色）。
- 有 `avatar_url` 时仍只显示图片，逻辑不变。
