# Linear 子任务功能分析（基于实际操作）

在 https://linear.app/hzwjs/team/HZW/all 登录态下，通过浏览器实际操作得到的子任务功能记录。

---

## 1. 列表页（All issues）

- **入口**：团队 All issues 视图，URL 为 `/team/HZW/all`。
- **分组与排序**：任务按分组（如状态 Status）展示；列表有 Grouping、Sub-grouping、Ordering、Display options 等。
- **交互**：点击某条 issue 进入该 issue 详情页（单页内导航，URL 变为 `/issue/HZW-xx/...`）。

### 1.1 子任务在列表中的展示（实际操作）

- **是否显示子任务行**：由列表的 **Display options → Show sub-issues** 控制。
  - **开启（默认）**：子任务作为独立行出现在列表中，与父任务同属一个分组（如同属 Done）；父任务行与子任务行均可见。
  - **关闭**：列表中**只保留顶层 issue**，子任务行全部隐藏。例如 Done 组由 5 行（含 HZW-10、HZW-9）变为 3 行（仅 HZW-7、HZW-8、HZW-5）。

- **视觉层级**：
  - **缩进**：子任务行相对父任务行向右缩进，多级时多级缩进（如 HZW-10 比 HZW-9 再缩进一层），便于区分父子。
  - **标题与层级路径**：子任务行的标题区域会带**父级路径**，用 `>` 连接，例如「子任务标题 > 测试子任务」「测试子任务 > 搭建项目框架」，表示该子任务所属父任务。
  - **父任务行的 x/y**：有子任务的 issue 在标题旁显示 **x/y**（完成数/总数），如「搭建项目框架 2/2」「测试子任务 1/1」。

- **列表的 Display options 中与子任务相关的项**：
  - **Show sub-issues**：是否在列表中显示子任务行；关闭则仅显示「顶层」issue。
  - **Nested sub-issues**：在 Show sub-issues 开启时出现；控制是否显示**多级**子任务（即子任务的子任务）。开启时列表中出现孙级等更深层行并做缩进；关闭时仅显示直接子任务（本次未在列表中切 Nested 对比，仅确认选项存在）。

- **行顺序**：子任务与父任务在同一分组内，按当前 Ordering 排序；子任务行可与父任务行相邻或按排序穿插，通过缩进与标题路径区分归属。

---

## 2. 父任务详情页内的子任务区块

以 **HZW-9 测试子任务** 为例（该 issue 本身是 HZW-5 的子任务，且其下有 1 个子任务）：

- **父任务身份**：在标题下方有链接 **「Sub-issue of HZW-5 搭建项目框架」**，并带进度 `2/2`。
- **Sub-issues 区块**：
  - 有 **「Collapse sub-issues section」** 按钮，可折叠/展开整块。
  - **「Display options」**：点击后弹出浮层，包含：
    - **Ordering**：排序方式（Manual, Title, Status, Priority, Assignee, Estimate, Updated, Created, Due date, Link count）+ Direction。
    - **Completed issues**：All / Past day / Past week / Past month / Current cycle / None。
    - **Nested sub-issues**：复选框，当前为勾选。
    - **Display properties**：可选展示列（Priority, SLA, ID, Status, Labels, Milestone, Due date, Links, Assignee, Pull requests）。
  - **「Create new sub-issue」** 按钮。

---

## 3. 创建子任务（实际操作流程）

- 在父任务详情页点击 **「Create new sub-issue」**。
- 在**同一页面内**展开内联表单（不跳转新页），包含：
  - **Issue title**（必填）。
  - **Add description...**（描述）。
  - **Set team**（当前为禁用）。
  - **Change status**（下拉）。
  - **Change priority**（未选时提示 "No priority is selected"）。
  - **Change assignee**（未选时 "Currently no one is assigned"）。
  - **Change labels**（下拉）。
  - **More actions**（更多）。
  - **Attach images, files or videos**。
  - **Discard sub-issue** / **Create**。
- 创建为内联轻量表单，保存后预期可继续追加下一个子任务（文档中有「保存后自动再开一个」的说明，本次未实际保存故未验证）。

---

## 4. 子任务列表展示（父任务视角）

- 在 Sub-issues 区块内，每条子任务为一行：
  - **可点击的标题**（如「子任务标题」），点击后进入该子任务详情。
  - **复选框**（完成态，只读），用于表示完成状态。
- 无子任务时应有占位提示；本次看到的是 1/1 且一条已完成。

---

## 5. 子任务详情页（以 HZW-10 子任务标题 为例）

- **面包屑**：`HZW-9 ›` → `HZW-10 子任务标题`，表明父任务为 HZW-9。
- **父任务链接**：正文区有链接 **「HZW-9 测试子任务」**，点击可回父任务。
- **结构**：与普通 issue 一致——标题、描述、评论、右侧 Properties（Done / Set priority / Assign / Labels / Project）。
- **嵌套子任务**：同样有 **「Create new sub-issue」** 按钮，即子任务可再挂子任务（与 Display options 中的「Nested sub-issues」一致）。

---

## 6. 多级子任务（实际操作验证）

**层级示例**：HZW-5（搭建项目框架）→ HZW-9（测试子任务）→ HZW-10（子任务标题），即祖父 → 父 → 子。

### 6.1 在顶层父任务 (HZW-5) 详情页的展示

- **Nested sub-issues 开启（默认）**：
  - Sub-issues 区块显示 **所有层级的后代**：先显示直接子任务「测试子任务」，其下**缩进**显示孙级「子任务标题」，二者之间用**虚线**连接，表示归属关系。
  - 标题旁计数为 **2/2**（当前展示 2 条，均已完成）。
  - 视觉上为树形：一级子任务与二级子任务通过缩进 + 虚线区分。

- **Nested sub-issues 关闭**：
  - 仅显示**直接子任务**：「测试子任务 1/1」一条；孙级「子任务标题」**不再出现在此列表**。
  - 标题旁计数变为 **1/1**（只计直接子任务）。
  - 多级结构仍存在，只是在当前父任务视角下不展开展示子孙级。

### 6.2 「Nested sub-issues」含义

- 控制的是**在父任务详情页的 Sub-issues 区块里，是否展开并展示子孙级**，而不是是否允许创建多级。
- 开启：树形展示，所有后代可见（缩进 + 虚线）。
- 关闭：只列直接子任务，孙级及更深层级在该区块中隐藏。

### 6.3 深度与创建

- 子任务详情页同样有「Create new sub-issue」，可继续往下挂子任务，**未发现层级深度限制**（本次仅验证到 3 级）。
- 列表页的 x/y 计数在有多级时，应理解为该 issue 下「直接 + 间接」子任务的完成/总数（与 Nested 开关无关，计数逻辑未在本次逐条验证）。

---

## 7. 小结（本次操作验证到的点）

| 项目 | 观察结果 |
|------|----------|
| **列表中子任务展示** | Show sub-issues 控制是否显示子任务行；开启时子任务独立成行、缩进、标题带「标题 > 父任务」路径，父任务行显示 x/y；关闭时仅显示顶层 issue |
| 列表子任务计数 | 列表项旁显示 x/y（完成/总数） |
| 父任务身份 | 详情页标题下「Sub-issue of XXX」链接 + 进度 |
| 子任务区块 | 可折叠、Display options、Create new sub-issue |
| 新建子任务 | 内联表单：标题、描述、状态/优先级/负责人/标签、Discard/Create |
| 子任务展示选项 | 排序、Completed 筛选、Nested sub-issues、展示列 |
| 子任务详情 | 面包屑 + 父任务链接，支持再建子任务（嵌套） |
| **多级子任务** | 祖父任务详情页可展示全部后代：开启 Nested 时树形缩进+虚线；关闭时仅直接子任务，计数随展示变化（如 2/2→1/1） |

未在本次操作中验证：Issue options 中的「Set parent / Remove parent」、从评论/列表转成子任务、状态联动（父/子自动完成）、列表筛选「仅父/仅子」、子任务层级深度上限。

---

*分析依据：在 Linear 团队 All issues 列表及 HZW-5 / HZW-9 / HZW-10 详情页内的真实点击与截图，含列表 Display options（Show sub-issues 开/关）与详情页 Nested sub-issues 开关对比。*
