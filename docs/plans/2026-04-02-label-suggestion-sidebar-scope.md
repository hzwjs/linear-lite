# 标签联想侧栏作用域修复 实现计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 按任务逐步执行本计划。

**目标：** 修复任务侧栏中的标签联想，使其在用户仍处于当前侧栏上下文内操作时保持打开，同时列表始终附着在标签区域下方，不再遮挡相邻属性行。

**方案概述：** 将标签联想从 `TaskEditor.vue` 中分散的输入联想逻辑，收敛为一个具有明确“侧栏作用域所有权”的独立交互单元。列表采用流式渲染，直接占据标签行下方空间；打开和关闭规则只由明确事件驱动，不再隐式依赖输入框失焦结果。先用失败测试锁定行为，再做最小实现，最后用真实浏览器回归验证。

**技术栈：** Vue 3、TypeScript、Vitest、Playwright（本机 Chromium）

---

### 任务 1：补齐行为测试，锁定侧栏作用域规则

**涉及文件：**
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditor.labelSuggestions.test.ts`
- 参考：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditor.vue`

**步骤 1：编写失败测试**

补充并明确以下测试场景：

```ts
it('在侧栏内点击其它属性控件后仍保持标签联想打开', async () => {
  // 打开联想
  // 点击例如项目行 / 日期行 / 进度行
  // 断言联想列表仍存在且仍位于标签区域下方
})

it('离开侧栏后关闭标签联想', async () => {
  // 打开联想
  // 点击侧栏外区域
  // 断言联想列表关闭
})

it('选择联想项后关闭并写入标签', async () => {
  // 打开联想
  // 点击联想项
  // 断言标签写入且列表关闭
})
```

**步骤 2：运行测试并确认失败**

运行：`npm test -- src/components/TaskEditor.labelSuggestions.test.ts`
预期：至少一个新增场景失败，暴露当前行为与目标不一致。

**步骤 3：整理测试断言边界**

断言必须覆盖：

```ts
expect(screen.getByRole('listbox')).toBeInTheDocument()
expect(screen.getByText('外系统审批')).toBeInTheDocument()
```

并检查列表 DOM 位于标签区域之后，而不是 `body` 上的浮层节点。

**步骤 4：再次运行测试并确认仍失败但定位准确**

运行：`npm test -- src/components/TaskEditor.labelSuggestions.test.ts`
预期：失败信息直接指向“关闭条件”或“渲染位置”问题，而不是无关异常。

**步骤 5：提交**

```bash
git add src/components/TaskEditor.labelSuggestions.test.ts
git commit -m "test: 锁定标签联想侧栏作用域行为"
```

### 任务 2：抽离标签联想为独立受控交互单元

**涉及文件：**
- 新增：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskLabelCombobox.vue`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditor.vue`
- 测试：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditor.labelSuggestions.test.ts`

**步骤 1：编写组件骨架**

新增组件最小接口：

```ts
const props = defineProps<{
  modelValue: string
  labels: { id?: number; name: string }[]
  projectId: number | null
  disabled: boolean
  sidebarRoot: HTMLElement | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  pick: [label: { id: number; name: string }]
  create: [name: string]
  remove: [index: number]
}>()
```

**步骤 2：实现单一状态入口**

内部状态只保留：

```ts
const open = ref(false)
const suggestions = ref<{ id: number; name: string }[]>([])
```

关闭规则只允许来自：

```ts
function close() {
  open.value = false
}

function handleEsc() { close() }
function handleOutsideSidebarPointerDown() { close() }
function handlePick() { close() }
function handleCommit() { close() }
```

**步骤 3：将列表改为流式渲染**

组件模板采用：

```vue
<div class="task-label-combobox">
  <div class="task-label-editor">...</div>
  <ul v-if="open && suggestions.length > 0" class="task-label-combobox__list" role="listbox">
    ...
  </ul>
</div>
```

不要使用 `Teleport`、`position: fixed`、面板坐标计算。

**步骤 4：在 `TaskEditor.vue` 中接入组件**

将原有标签输入、建议列表、相关事件逻辑替换为：

```vue
<TaskLabelCombobox
  v-model="labelInput"
  :labels="formLabels"
  :project-id="effectiveProjectId"
  :disabled="mode !== 'edit' || !task"
  :sidebar-root="editorPanelRef"
  @pick="..."
  @create="..."
  @remove="..."
/>
```

同时删除 `TaskEditor.vue` 中与标签联想直接相关的分散逻辑：
- `labelSuggestions`
- `labelSuggestionsOpen`
- `fetchLabelSuggestions()`
- `onLabelInputFocus()`
- `onLabelInputInput()`
- `onLabelInputBlur()`
- `handleLabelSuggestionFocusIn()`
- `handleLabelSuggestionPointerDown()`

保留与表单值同步直接相关的最小逻辑。

**步骤 5：运行测试并确认通过**

运行：`npm test -- src/components/TaskEditor.labelSuggestions.test.ts`
预期：全部 PASS。

**步骤 6：提交**

```bash
git add src/components/TaskLabelCombobox.vue src/components/TaskEditor.vue src/components/TaskEditor.labelSuggestions.test.ts
git commit -m "fix: 重构标签联想侧栏作用域交互"
```

### 任务 3：补充组件级测试，覆盖输入联想契约

**涉及文件：**
- 新增：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskLabelCombobox.test.ts`
- 参考：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskLabelCombobox.vue`

**步骤 1：编写失败测试**

覆盖组件自身契约：

```ts
it('focus 和 input 会打开联想')
it('侧栏内点击不会关闭')
it('侧栏外点击会关闭')
it('mousedown 选择联想项时会发出 pick 并关闭')
it('空输入回车时优先选择第一项')
```

**步骤 2：运行测试并确认失败**

运行：`npm test -- src/components/TaskLabelCombobox.test.ts`
预期：FAIL，提示组件尚未完整实现契约。

**步骤 3：补齐最小实现或必要调整**

如果任务 2 的实现还缺少事件边界，则只补齐对应逻辑，例如：

```ts
if (!sidebarRoot?.contains(target)) close()
```

不要顺带改动无关样式或表单逻辑。

**步骤 4：运行测试并确认通过**

运行：`npm test -- src/components/TaskLabelCombobox.test.ts`
预期：PASS。

**步骤 5：提交**

```bash
git add src/components/TaskLabelCombobox.test.ts src/components/TaskLabelCombobox.vue
git commit -m "test: 覆盖标签联想组件交互契约"
```

### 任务 4：真实浏览器回归验证，按用户路径验证而非 DOM 猜测

**涉及文件：**
- 不改代码
- 验证目标：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditor.vue`

**步骤 1：启动并登录真实浏览器**

运行示例：

```bash
node scripts/manual-playwright-check.js
```

或直接用 `playwright-core` + 本机 Chromium：

```js
// 登录 http://localhost:5173/
// 打开 /tasks/HZW-37
```

**步骤 2：验证用户主路径**

严格按以下路径验证：

1. 打开标签输入联想
2. 点击状态、优先级、负责人、计划开始、截止日期、进度、项目、完成时间、侧栏空白区域
3. 断言联想列表仍保持打开且仍位于标签区域下方
4. 点击侧栏外区域，断言关闭

**步骤 3：记录证据**

至少记录以下结果：

```text
- 点击目标选择器
- 点击后 listbox 是否存在
- listbox 的父容器是否仍在标签区域 DOM 中
- 相邻属性行是否仍可命中
```

**步骤 4：运行回归测试集**

运行：`npm test -- src/components/TaskEditor.labelSuggestions.test.ts src/components/TaskLabelCombobox.test.ts src/store/taskStore.test.ts`
预期：全部 PASS。

**步骤 5：提交**

```bash
git add src/components/TaskEditor.vue src/components/TaskLabelCombobox.vue src/components/TaskLabelCombobox.test.ts src/components/TaskEditor.labelSuggestions.test.ts
git commit -m "fix: 完成标签联想侧栏交互回归验证"
```
