# 任务详情优化梳理 实现计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 按任务逐步执行本计划。

**目标：** 基于现有任务详情实现，完成一批不改变核心领域模型的高优先级体验优化，包括属性面板分组、截止日期时态显式化、附件空态折叠、活动流降噪、翻页上下文补充、子任务状态语义强化与评论输入区轻量增强。

**方案概述：** 优先处理只涉及前端展示与轻量状态传递的改动，不触碰 `progressPercent` 语义重构与子任务协作信息扩展。实现上以 `TaskEditor.vue` 为主，少量补充 store / utils / i18n，并用 Vitest 覆盖新增纯函数与关键展示规则。

**技术栈：** Vue 3、TypeScript、Pinia、Vue I18n、Vitest。

**设计依据：** `docs/plans/2026-04-10-task-detail-review-design.md`

---

### 任务 1：截止日期时态文案与逾期状态纯函数

**涉及文件：**
- 新增：`src/utils/taskDueState.ts`
- 新增：`src/utils/taskDueState.test.ts`

**步骤 1：编写失败测试**

```ts
import { describe, expect, it } from 'vitest'
import { getTaskDueState } from './taskDueState'

describe('getTaskDueState', () => {
  it('returns overdue when due date is before today', () => {
    const result = getTaskDueState(Date.parse('2026-04-01T00:00:00+08:00'), new Date('2026-04-10T12:00:00+08:00'))
    expect(result.kind).toBe('overdue')
    expect(result.dayCount).toBe(9)
  })
})
```

**步骤 2：运行测试并确认失败**

运行：`npx vitest run src/utils/taskDueState.test.ts`
预期：FAIL，提示 `getTaskDueState` 未定义或结果不匹配。

**步骤 3：编写最小实现**

```ts
export function getTaskDueState(dueDateMs?: number | null, now: Date = new Date()) {
  if (dueDateMs == null) return { kind: 'none', dayCount: 0 }
  // 以本地日历日为单位比较，避免时分秒噪音。
}
```

**步骤 4：运行测试并确认通过**

运行：`npx vitest run src/utils/taskDueState.test.ts`
预期：PASS

**步骤 5：提交**

```bash
git add src/utils/taskDueState.ts src/utils/taskDueState.test.ts
git commit -m "feat(task-detail): add due state utility"
```

---

### 任务 2：TaskEditor 侧栏属性分组与截止日期提示

**涉及文件：**
- 修改：`src/components/TaskEditor.vue`
- 修改：`src/i18n/messages/zh-CN.ts`
- 修改：`src/i18n/messages/en.ts`
- 测试：`src/components/TaskTypography.test.ts` 或新增 `src/components/TaskEditorDueState.test.ts`

**步骤 1：编写失败测试**

- 验证编辑器右侧出现新的分组标题或分组容器。
- 验证存在截止日期时显示 `today due` / `overdue` / `in X days` 对应文案。

**步骤 2：运行测试并确认失败**

运行：`npx vitest run src/components/TaskEditorDueState.test.ts`
预期：FAIL

**步骤 3：编写最小实现**

- 将属性面板拆成执行 / 时间 / 归档三组。
- 在 due date 行下方增加辅助状态文案。
- 已逾期时对 due date 辅助文案使用警示色，但不修改状态控件语义。

**步骤 4：运行测试并确认通过**

运行：`npx vitest run src/components/TaskEditorDueState.test.ts`
预期：PASS

**步骤 5：提交**

```bash
git add src/components/TaskEditor.vue src/i18n/messages/zh-CN.ts src/i18n/messages/en.ts src/components/TaskEditorDueState.test.ts
git commit -m "feat(task-detail): group sidebar props and show due state"
```

---

### 任务 3：附件空态折叠

**涉及文件：**
- 修改：`src/components/TaskEditor.vue`
- 测试：新增 `src/components/TaskEditorAttachments.test.ts`

**步骤 1：编写失败测试**

- 无附件时只显示单行入口或折叠标题，不显示完整空文案块。
- 有附件时仍显示附件列表。

**步骤 2：运行测试并确认失败**

运行：`npx vitest run src/components/TaskEditorAttachments.test.ts`
预期：FAIL

**步骤 3：编写最小实现**

- 当 `attachments.length === 0` 且未上传中时，默认收起 section body。
- 保留现有上传按钮与附件输入逻辑。

**步骤 4：运行测试并确认通过**

运行：`npx vitest run src/components/TaskEditorAttachments.test.ts`
预期：PASS

**步骤 5：提交**

```bash
git add src/components/TaskEditor.vue src/components/TaskEditorAttachments.test.ts
git commit -m "feat(task-detail): collapse empty attachments section"
```

---

### 任务 4：活动流降噪与 actor 名称统一

**涉及文件：**
- 修改：`src/utils/taskActivity.ts`
- 新增：`src/utils/taskActivityGroup.ts`
- 新增：`src/utils/taskActivityGroup.test.ts`
- 修改：`src/components/TaskEditor.vue`

**步骤 1：编写失败测试**

- 相同 actor、相同字段、短时间连续变更应被分组成一条展示项。
- actor 名称格式在同一组内应稳定。

**步骤 2：运行测试并确认失败**

运行：`npx vitest run src/utils/taskActivityGroup.test.ts`
预期：FAIL

**步骤 3：编写最小实现**

- 提取纯函数对活动列表做轻量分组。
- `TaskEditor` 使用分组后的展示结果渲染。
- 保持原始活动数据不变，只在展示层降噪。

**步骤 4：运行测试并确认通过**

运行：`npx vitest run src/utils/taskActivityGroup.test.ts`
预期：PASS

**步骤 5：提交**

```bash
git add src/utils/taskActivity.ts src/utils/taskActivityGroup.ts src/utils/taskActivityGroup.test.ts src/components/TaskEditor.vue
git commit -m "feat(task-detail): reduce noise in activity list"
```

---

### 任务 5：翻页来源上下文

**涉及文件：**
- 修改：`src/store/issuePanelStore.ts`
- 修改：`src/views/BoardViewContent.vue`
- 修改：`src/components/TaskEditor.vue`
- 测试：`src/store/issuePanelStore.test.ts`

**步骤 1：编写失败测试**

- 从列表上下文打开任务时，store 能记录来源名称。
- 无上下文时，任务详情不展示来源名称。

**步骤 2：运行测试并确认失败**

运行：`npx vitest run src/store/issuePanelStore.test.ts`
预期：FAIL

**步骤 3：编写最小实现**

- 在打开任务详情的调用链中传入来源视图标签。
- `TaskEditor` header 在 `position / total` 前展示上下文名称。

**步骤 4：运行测试并确认通过**

运行：`npx vitest run src/store/issuePanelStore.test.ts`
预期：PASS

**步骤 5：提交**

```bash
git add src/store/issuePanelStore.ts src/views/BoardViewContent.vue src/components/TaskEditor.vue src/store/issuePanelStore.test.ts
git commit -m "feat(task-detail): show navigation source context"
```

---

### 任务 6：子任务状态语义强化

**涉及文件：**
- 修改：`src/components/TaskRowStatusPicker.vue`
- 修改：`src/components/TaskEditor.vue`
- 测试：新增 `src/components/TaskRowStatusPicker.test.ts`

**步骤 1：编写失败测试**

- 不同 status 显示不同视觉类名或 aria 文案。
- 子任务行状态控件 hover / aria-label 能明确表达“修改状态”。

**步骤 2：运行测试并确认失败**

运行：`npx vitest run src/components/TaskRowStatusPicker.test.ts`
预期：FAIL

**步骤 3：编写最小实现**

- 为状态触发器补充更明确的视觉与可访问性语义。
- 不改变现有交互路径，只强化识别性。

**步骤 4：运行测试并确认通过**

运行：`npx vitest run src/components/TaskRowStatusPicker.test.ts`
预期：PASS

**步骤 5：提交**

```bash
git add src/components/TaskRowStatusPicker.vue src/components/TaskEditor.vue src/components/TaskRowStatusPicker.test.ts
git commit -m "feat(task-detail): clarify sub-issue status control"
```

---

### 任务 7：评论输入区轻量增强

**涉及文件：**
- 修改：`src/components/TaskEditor.vue`
- 修改：`src/components/TiptapEditor.vue`
- 修改：`src/i18n/messages/zh-CN.ts`
- 修改：`src/i18n/messages/en.ts`
- 测试：新增 `src/components/TaskEditorComments.test.ts`

**步骤 1：编写失败测试**

- 评论输入区显示发送快捷键提示。
- `Cmd+Enter` 或 `Ctrl+Enter` 可触发发送。

**步骤 2：运行测试并确认失败**

运行：`npx vitest run src/components/TaskEditorComments.test.ts`
预期：FAIL

**步骤 3：编写最小实现**

- 为评论编辑器增加快捷键提交。
- 将“提醒成员”区域文案改为更明确的可操作提示。

**步骤 4：运行测试并确认通过**

运行：`npx vitest run src/components/TaskEditorComments.test.ts`
预期：PASS

**步骤 5：提交**

```bash
git add src/components/TaskEditor.vue src/components/TiptapEditor.vue src/i18n/messages/zh-CN.ts src/i18n/messages/en.ts src/components/TaskEditorComments.test.ts
git commit -m "feat(task-detail): improve comment composer affordance"
```

---

## 验证命令（全量）

```bash
npx vitest run src/utils/taskDueState.test.ts src/components/TaskEditorDueState.test.ts src/components/TaskEditorAttachments.test.ts src/utils/taskActivityGroup.test.ts src/store/issuePanelStore.test.ts src/components/TaskRowStatusPicker.test.ts src/components/TaskEditorComments.test.ts
npm run build
```

预期：

- 所有新增或修改测试 PASS
- 构建通过，无类型错误

---

## 执行顺序建议

1. 先完成任务 1-3，解决时态表达与布局密度问题。
2. 再完成任务 4-7，逐步收紧信息架构与交互语义。
3. 本计划不包含 `progressPercent` 语义重构与子任务行字段扩展；若后续确认推进，应单独起新设计与计划。
