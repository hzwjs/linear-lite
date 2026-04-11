# Avatar 背景色改造实施方案（15 色固定色板 + username 映射）

**日期**: 2026-04-11  
**状态**: Implemented（2026-04-11）  
**目标**: 在现有代码基础上，将 fallback 头像背景色从 `userId -> 24 色 OKLCH` 迁移为 `username -> 15 色固定色板`，满足“颜色强区分、快速定位人员”的业务诉求。

---

## 1. 代码现状基线

### 1.1 核心算法与测试

- 当前算法文件：`src/utils/avatar.ts`
- 当前行为：
  - `AVATAR_BACKGROUND_PALETTE` 为 24 色动态生成色板
  - `getAvatarColor(userId: number)` 使用 `userId` 映射颜色
- 当前测试文件：`src/utils/avatar.test.ts`
  - 断言色板长度为 24
  - 断言白字对比度 AA（`>= 4.5`）

### 1.2 业务使用点（fallback 头像）

- `src/components/TaskRowAssigneePicker.vue`
- `src/components/TaskListView.vue`
- `src/components/TaskCard.vue`
- `src/components/issue-filters/AddIssueFilterMenu.vue`

以上 4 处都直接依赖 `getAvatarColor(...)`，迁移时需统一切换，避免出现跨页面颜色不一致。

---

## 2. 目标约束（本次）

1. 固定 15 色色板（不使用纯色）。
2. 绿系、紫系尽量弱化，不作为主导色相。
3. 映射基于 `username`，不使用连续数字 `userId`。
4. 同一用户名跨页面、跨会话颜色稳定。
5. fallback 头像文字可读性不下降（白字对比度继续满足 AA）。

---

## 3. 技术方案

### 3.1 新接口

在 `src/utils/avatar.ts` 新增主入口：

- `getAvatarColorByUsername(username: string): { background: string; color: string }`

并保留过渡兼容：

- `getAvatarColor(userId: number)`（短期保留，完成迁移后可删除）

### 3.2 用户名归一化

颜色映射前统一规范化 username：

1. `trim()`
2. `toLowerCase()`
3. `normalize('NFKC')`

空字符串走固定兜底槽位。

### 3.3 哈希与索引

- 使用稳定字符串哈希（`FNV-1a` 或 `djb2`）。
- `idx = hash(normalizedUsername) % 15`。
- 颜色返回固定白字（`#fff`），通过测试保证对比度。

### 3.4 固定 15 色色板

色板放在 `avatar.ts` 常量中，命名建议：

- `AVATAR_BACKGROUND_PALETTE_15`

规则：

1. 非纯色（降低饱和度与纯度）。
2. 避免高纯绿、高纯紫。
3. 各色相间距足够，保证小头像下可快速分组识别。

---

## 4. 执行拆分（可跟踪）

### M1 规格冻结

**改动**:

- 在 `avatar.ts` 引入 15 色常量和新函数签名。
- 在文件注释写清映射规则与兼容策略。

**完成定义（DoD）**:

- 代码中可见 15 色常量与 `getAvatarColorByUsername` 定义。
- 文档/注释中明确“username 归一化 + 哈希映射”。

### M2 核心算法落地

**改动**:

- 实现用户名归一化 + 稳定哈希 + `% 15` 映射。
- 处理空字符串兜底。

**DoD**:

- 同一用户名多次调用返回一致颜色。
- 大小写、前后空格变化不会改变颜色映射。

### M3 全量调用点切换

**改动**:

- 将 4 个组件 fallback 分支改为 `getAvatarColorByUsername(user.username)`。

**DoD**:

- 4 个文件切换完成。
- fallback 路径不再依赖 `user.id` 取色。

### M4 测试更新与补强

**改动**:

- 更新 `avatar.test.ts`：
  - 色板长度断言改为 15
  - 唯一性断言为 15
  - 用户名稳定映射断言
  - 对比度断言保持 `>= 4.5`
- 增加 1 个组件级验证（建议优先 `TaskRowAssigneePicker`）确认 style 来源于 username。

**DoD**:

- 相关测试通过且覆盖新映射规则。

### M5 回归与收口

**改动**:

- 执行测试与构建回归。
- 手工验证 4 个入口视觉一致性。

**DoD**:

- `pnpm test` 通过
- `pnpm build` 通过
- 手工验收通过并记录结果

---

## 5. 验收清单（执行时打勾）

- [x] 同一成员在列表、卡片、筛选、assignee picker 颜色一致（均 `getAvatarColorByUsername(username)`）
- [x] 用户名大小写/空格变化不导致颜色漂移（单测覆盖）
- [x] 色板为固定 15 色且无明显纯绿/纯紫主导色
- [x] fallback 头像白字可读性满足 AA（单测对比度 ≥4.5）
- [x] `npm run test` 与 `npm run build` 全绿

---

## 6. 风险与回滚

### 风险

1. 用户改名会导致颜色变化（这是 username 映射的天然结果）。
2. NFKC 归一化与历史数据不一致时，极少数用户颜色会发生迁移。
3. 主观审美争议会反复拖慢上线节奏。

### 对策

1. 本期接受“改名换色”；若后续要“改名不换色”，新增后端持久化 color token。
2. 将归一化规则写入单测，避免后续改动导致漂移。
3. 先锁 token，再做一次集中评审，不在开发中反复调色。

### 回滚策略

若上线后反馈不达预期，可在 `avatar.ts` 一处切回旧实现：

- 恢复 `getAvatarColor(userId)` 调用路径
- 暂不删除旧逻辑，保留快速开关窗口

