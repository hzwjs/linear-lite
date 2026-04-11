# 评论区分层与编辑器复用 实现计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 按任务逐步执行本计划。

**目标：** 在桌面端将当前“线性评论”升级为“可回复的分层评论”，并复用现有编辑器能力（含图片）作为评论输入主链路，保证可回归、可审计、可灰度。

**方案概述：** 方案采用“后端先补数据结构与约束，前端再做线程化渲染与交互”的顺序，避免 UI 先行造成接口返工。评论输入统一复用 `TiptapEditor`，主评论与回复共用同一能力栈；回复层级限制为 2 层并支持折叠展示。执行中按 TDD 小步提交，每个任务强制保留验证命令与证据，满足审计追踪。

**技术栈：** Vue 3 + TypeScript + Vitest、Spring Boot 3 + MyBatis-Plus + MySQL 8。

---

## 设计方案（可审计）

### 一、范围与非范围

- 范围：桌面端任务详情评论区（展示、回复、删除、输入、mention、图片）。
- 范围：后端评论模型扩展（父子关系、深度限制、列表返回结构）。
- 非范围：移动端适配。
- 非范围：超过 2 层的真实嵌套展示。

### 二、产品与交互决策

- 输入能力：复用现有编辑器（`TiptapEditor`），评论支持图片、@、代码块与基础样式。
- 层级规则：仅 2 层可视层级；第 3 层及更深回复自动并入第 2 层。
- 展示规则：每个一级评论默认展示前 3 条回复，超出显示“查看 N 条回复”。
- 删除规则：保留当前 3 分钟可删窗口；入口降噪但可见（非 hover-only）。
- 跳转规则：回复输入框在评论卡片内联展开，不跳到底部主输入框。

### 三、数据与接口设计

- `task_comments` 新增字段：`parent_id`、`root_id`、`depth`。
- 创建评论接口新增可选入参：`parentId`。
- 列表接口返回扩展字段：`parentId`、`rootId`、`depth`（前端据此构造树）。
- 服务端统一约束：`depth > 1` 时自动归并为 `depth = 1` 的回复（即展示第 2 层）。

### 四、审计追踪机制

- 执行技能引用：`@executing-plans`、`@verification-before-completion`。
- 审计记录文件：`docs/reviews/2026-04-11-comment-thread-optimization-audit.md`（执行时创建并持续追加）。
- 每个任务必须记录：开始时间、结束时间、命令、结果、对应 commit hash。
- 审计门禁：无测试输出证据、无 commit hash、无回归结果，任务不得标记完成。

---

### 任务 1：后端评论模型扩展（DDL + 实体 + DTO）

**涉及文件：**
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/linear-lite-server/src/main/resources/schema.sql`
- 新增：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/linear-lite-server/src/main/resources/schema-v12-comment-threading.sql`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/linear-lite-server/src/main/java/com/linearlite/server/entity/TaskComment.java`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/linear-lite-server/src/main/java/com/linearlite/server/dto/CreateTaskCommentRequest.java`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/linear-lite-server/src/main/java/com/linearlite/server/dto/TaskCommentResponse.java`
- 测试：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/linear-lite-server/src/test/java/com/linearlite/server/service/TaskCommentServiceTest.java`

**步骤 1：编写失败测试（DTO/实体字段存在）**

```java
@Test
void createSupportsParentIdAndDepthFields() {
    CreateTaskCommentRequest req = new CreateTaskCommentRequest();
    req.setBody("reply");
    req.setParentId(10L);
    assertEquals(10L, req.getParentId());

    TaskCommentResponse resp = new TaskCommentResponse();
    resp.setParentId(10L);
    resp.setRootId(1L);
    resp.setDepth(1);
    assertEquals(1, resp.getDepth());
}
```

**步骤 2：运行测试并确认失败**

运行：`rtk mvn -q -f linear-lite-server/pom.xml -Dtest=TaskCommentServiceTest test`
预期：FAIL，提示 `getParentId/setParentId/getDepth` 等符号不存在。

**步骤 3：编写最小实现**

```java
// TaskComment.java
private Long parentId;
private Long rootId;
private Integer depth;

// CreateTaskCommentRequest.java
private Long parentId;

// TaskCommentResponse.java
private Long parentId;
private Long rootId;
private Integer depth;
```

同时在 `schema-v12-comment-threading.sql` 增量定义：

```sql
ALTER TABLE task_comments
  ADD COLUMN parent_id BIGINT NULL AFTER author_id,
  ADD COLUMN root_id BIGINT NULL AFTER parent_id,
  ADD COLUMN depth TINYINT NOT NULL DEFAULT 0 AFTER root_id;

CREATE INDEX idx_task_comments_root_created ON task_comments (task_id, root_id, created_at, id);
CREATE INDEX idx_task_comments_parent_created ON task_comments (parent_id, created_at, id);
```

并将同等 DDL 归档进 `schema.sql`（遵循“增量最终归档”约定）。

**步骤 4：运行测试并确认通过**

运行：`rtk mvn -q -f linear-lite-server/pom.xml -Dtest=TaskCommentServiceTest test`
预期：PASS。

**步骤 5：提交**

```bash
rtk git add linear-lite-server/src/main/resources/schema.sql \
  linear-lite-server/src/main/resources/schema-v12-comment-threading.sql \
  linear-lite-server/src/main/java/com/linearlite/server/entity/TaskComment.java \
  linear-lite-server/src/main/java/com/linearlite/server/dto/CreateTaskCommentRequest.java \
  linear-lite-server/src/main/java/com/linearlite/server/dto/TaskCommentResponse.java \
  linear-lite-server/src/test/java/com/linearlite/server/service/TaskCommentServiceTest.java
rtk git commit -m "feat(server): 评论模型支持父子层级字段"
```

---

### 任务 2：后端创建/查询逻辑支持分层回复与 2 层限制

**涉及文件：**
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/linear-lite-server/src/main/java/com/linearlite/server/service/TaskCommentService.java`
- 测试：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/linear-lite-server/src/test/java/com/linearlite/server/service/TaskCommentServiceTest.java`

**步骤 1：编写失败测试（回复深度归并）**

```java
@Test
void createReplyCapsDepthAtSecondLevel() {
    // parent(depth=1) 上继续回复，期望新评论 depth 仍为 1，root_id 指向一级评论
    // 断言保存到 mapper 的 TaskComment.depth == 1
}
```

**步骤 2：运行测试并确认失败**

运行：`rtk mvn -q -f linear-lite-server/pom.xml -Dtest=TaskCommentServiceTest test`
预期：FAIL，深度/根节点断言不成立。

**步骤 3：编写最小实现**

```java
// TaskCommentService#create
Long parentId = req.getParentId();
TaskComment parent = null;
if (parentId != null) {
    parent = taskCommentMapper.selectById(parentId);
    if (parent == null || !parent.getTaskId().equals(task.getId())) {
        throw new ResourceNotFoundException("父评论不存在");
    }
}

int depth = 0;
Long rootId = null;
if (parent != null) {
    depth = 1;
    rootId = parent.getDepth() != null && parent.getDepth() > 0
            ? parent.getRootId()
            : parent.getId();
}
c.setParentId(parentId);
c.setRootId(rootId);
c.setDepth(depth);
```

`listByTaskKey` 中返回扩展字段，并保持按 `created_at, id` 稳定排序。

**步骤 4：运行测试并确认通过**

运行：`rtk mvn -q -f linear-lite-server/pom.xml -Dtest=TaskCommentServiceTest test`
预期：PASS。

**步骤 5：提交**

```bash
rtk git add linear-lite-server/src/main/java/com/linearlite/server/service/TaskCommentService.java \
  linear-lite-server/src/test/java/com/linearlite/server/service/TaskCommentServiceTest.java
rtk git commit -m "feat(server): 评论创建支持回复与二层限制"
```

---

### 任务 3：前端 API 契约升级（评论 DTO + create 入参）

**涉及文件：**
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/services/api/taskComments.ts`
- 测试：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditorComments.test.ts`
- 测试：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditorAttachments.test.ts`
- 测试：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditorDueState.test.ts`

**步骤 1：编写失败测试（create 增加 parentId）**

```ts
expect(taskCommentsApi.create).toHaveBeenCalledWith('ENG-1', {
  body: 'Hello',
  mentionedUserIds: [],
  parentId: null
})
```

**步骤 2：运行测试并确认失败**

运行：`rtk npm run test -- src/components/TaskEditorComments.test.ts`
预期：FAIL，调用签名不匹配。

**步骤 3：编写最小实现**

```ts
export interface TaskCommentDto {
  id: number
  authorId: number
  authorName: string
  body: string
  createdAt: string
  deletable: boolean
  parentId: number | null
  rootId: number | null
  depth: number
}

create(
  taskKey: string,
  payload: { body: string; mentionedUserIds: number[]; parentId: number | null }
): Promise<TaskCommentDto> {
  return api.post<ApiResponse<TaskCommentDto>>(`/tasks/${encodeURIComponent(taskKey)}/comments`, payload)
    .then((res) => toComment(unwrap(res)))
}
```

**步骤 4：运行测试并确认通过**

运行：
- `rtk npm run test -- src/components/TaskEditorComments.test.ts`
- `rtk npm run test -- src/components/TaskEditorAttachments.test.ts src/components/TaskEditorDueState.test.ts`

预期：PASS。

**步骤 5：提交**

```bash
rtk git add src/services/api/taskComments.ts \
  src/components/TaskEditorComments.test.ts \
  src/components/TaskEditorAttachments.test.ts \
  src/components/TaskEditorDueState.test.ts
rtk git commit -m "refactor(frontend): 评论 API 升级为分层契约"
```

---

### 任务 4：前端线程构建工具（平铺数据 -> 一级评论 + 二级回复）

**涉及文件：**
- 新增：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/utils/commentThread.ts`
- 新增：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/utils/commentThread.test.ts`

**步骤 1：编写失败测试（构建树与折叠计数）**

```ts
it('groups replies under root and caps visible replies at 3', () => {
  const threaded = buildCommentThreads(rows)
  expect(threaded[0].replies.length).toBe(5)
  expect(threaded[0].visibleReplies.length).toBe(3)
  expect(threaded[0].hiddenReplyCount).toBe(2)
})
```

**步骤 2：运行测试并确认失败**

运行：`rtk npm run test -- src/utils/commentThread.test.ts`
预期：FAIL，`buildCommentThreads` 未定义。

**步骤 3：编写最小实现**

```ts
export function buildCommentThreads(rows: TaskCommentDto[]) {
  const roots = rows.filter((r) => (r.depth ?? 0) === 0)
  const repliesByRoot = new Map<number, TaskCommentDto[]>()
  for (const row of rows) {
    if ((row.depth ?? 0) === 0) continue
    const rid = row.rootId ?? row.parentId
    if (!rid) continue
    const list = repliesByRoot.get(rid) ?? []
    list.push(row)
    repliesByRoot.set(rid, list)
  }
  return roots.map((root) => {
    const replies = (repliesByRoot.get(root.id) ?? []).sort((a, b) => a.id - b.id)
    return {
      root,
      replies,
      visibleReplies: replies.slice(0, 3),
      hiddenReplyCount: Math.max(0, replies.length - 3)
    }
  })
}
```

**步骤 4：运行测试并确认通过**

运行：`rtk npm run test -- src/utils/commentThread.test.ts`
预期：PASS。

**步骤 5：提交**

```bash
rtk git add src/utils/commentThread.ts src/utils/commentThread.test.ts
rtk git commit -m "feat(frontend): 新增评论线程构建与折叠计算"
```

---

### 任务 5：TaskEditor 评论区改为线程渲染与内联回复

**涉及文件：**
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditor.vue`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditorComments.test.ts`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/i18n/messages/zh-CN.ts`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/i18n/messages/en.ts`

**步骤 1：编写失败测试（回复输入与折叠入口）**

```ts
it('opens inline reply editor under comment row', async () => {
  // 点击“回复”后，指定评论卡片下出现 TiptapEditor
})

it('shows "查看 N 条回复" when replies exceed 3', async () => {
  // 模拟 5 条回复，断言折叠入口文案
})
```

**步骤 2：运行测试并确认失败**

运行：`rtk npm run test -- src/components/TaskEditorComments.test.ts`
预期：FAIL，DOM 查询不到线程化结构。

**步骤 3：编写最小实现（复用编辑器）**

```vue
<div v-for="thread in commentThreads" :key="thread.root.id" class="task-comment-row">
  <CommentCard :comment="thread.root" @reply="openReplyEditor(thread.root.id)" />

  <div v-if="thread.visibleReplies.length" class="task-reply-list">
    <CommentCard v-for="r in thread.visibleReplies" :key="r.id" :comment="r" :is-reply="true" />
  </div>

  <button v-if="thread.hiddenReplyCount > 0" @click="expandReplies(thread.root.id)">
    {{ t('taskEditor.viewMoreReplies', { count: thread.hiddenReplyCount }) }}
  </button>

  <div v-if="activeReplyParentId === thread.root.id" class="comment-compose comment-compose--inline">
    <TiptapEditor
      v-model="replyBody"
      :mention-members="mentionMembersForCommentEditor"
      :placeholder="t('taskEditor.replyPlaceholder')"
      :min-height="56"
    />
  </div>
</div>
```

提交回复时调用：

```ts
await taskCommentsApi.create(props.task.id, {
  body,
  mentionedUserIds: ids,
  parentId: activeReplyParentId.value
})
```

**步骤 4：运行测试并确认通过**

运行：
- `rtk npm run test -- src/components/TaskEditorComments.test.ts`
- `rtk npm run test -- src/components/TaskEditorAttachments.test.ts src/components/TaskEditorDueState.test.ts`

预期：PASS。

**步骤 5：提交**

```bash
rtk git add src/components/TaskEditor.vue \
  src/components/TaskEditorComments.test.ts \
  src/i18n/messages/zh-CN.ts \
  src/i18n/messages/en.ts
rtk git commit -m "feat(frontend): 评论区线程化渲染与内联回复"
```

---

### 任务 6：删除入口降噪但可见 + 桌面可用性回归

**涉及文件：**
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditor.vue`
- 测试：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditorComments.test.ts`

**步骤 1：编写失败测试（删除按钮可见性与可点击）**

```ts
it('keeps delete action visible for deletable comments', async () => {
  // 断言删除按钮常显（非 hover-only）且可触发 delete API
})
```

**步骤 2：运行测试并确认失败**

运行：`rtk npm run test -- src/components/TaskEditorComments.test.ts`
预期：FAIL，按钮状态与断言不一致。

**步骤 3：编写最小实现**

```vue
<div class="task-comment-actions">
  <button type="button" class="task-comment-reply" @click="openReplyEditor(c.id)">{{ t('taskEditor.reply') }}</button>
  <button
    v-if="c.deletable"
    type="button"
    class="task-comment-delete task-comment-delete--subtle"
    @click="deleteCommentRow(c)"
  >
    {{ t('taskEditor.deleteComment') }}
  </button>
</div>
```

**步骤 4：运行测试并确认通过**

运行：`rtk npm run test -- src/components/TaskEditorComments.test.ts`
预期：PASS。

**步骤 5：提交**

```bash
rtk git add src/components/TaskEditor.vue src/components/TaskEditorComments.test.ts
rtk git commit -m "refactor(frontend): 评论操作栏降噪并保持可见"
```

---

### 任务 7：全链路回归、审计归档与灰度开关

**涉及文件：**
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/TaskEditor.vue`（如需开关）
- 新增：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/docs/reviews/2026-04-11-comment-thread-optimization-audit.md`

**步骤 1：补充失败用例并锁定回归面**

```ts
it('submits root comment with parentId null', async () => {
  // 断言 parentId === null
})

it('submits reply with parentId set', async () => {
  // 断言 parentId === rootCommentId
})
```

**步骤 2：运行前端测试并确认通过**

运行：`rtk npm run test -- src/components/TaskEditorComments.test.ts src/utils/commentThread.test.ts`
预期：PASS。

**步骤 3：运行后端测试并确认通过**

运行：`rtk mvn -q -f linear-lite-server/pom.xml -Dtest=TaskCommentServiceTest test`
预期：PASS。

**步骤 4：运行构建并确认通过**

运行：
- `rtk npm run build`
- `rtk mvn -q -f linear-lite-server/pom.xml -DskipTests compile`

预期：均成功，无类型错误与编译错误。

**步骤 5：审计记录与提交**

审计文档至少记录以下内容：

```md
- 任务编号：7
- 命令：rtk npm run build
- 结果：PASS
- 证据：终端输出摘要（成功行）
- 提交：<commit-hash>
- 执行人：<name>
- 时间：<start/end>
```

提交：

```bash
rtk git add docs/reviews/2026-04-11-comment-thread-optimization-audit.md
rtk git commit -m "docs: 归档评论区优化执行审计记录"
```

---

## 验收标准（DoD）

- 评论主输入与回复输入均复用现有编辑器，图片可上传/粘贴并稳定展示。
- 评论展示为“一级评论 + 二级回复”，第 3 层自动归并为第 2 层。
- 每个一级评论回复超过 3 条时可折叠/展开。
- 后端接口与数据库字段支持 `parentId/rootId/depth`，并有测试覆盖。
- 前后端关键测试、构建、编译全部通过。
- 审计记录文件完整，包含任务-命令-结果-commit 的一一映射。

## 风险与回滚

- 风险：历史评论没有 `depth` 字段时渲染异常。
- 缓解：后端返回时对空值兜底为 `depth=0`，前端线程构建函数做默认值保护。
- 回滚：保留旧列表渲染分支，使用布尔开关（如 `enableThreadedComments`）快速回切。

## 执行指令

- 本计划执行必须使用：`@executing-plans`
- 完成前必须执行验收：`@verification-before-completion`

