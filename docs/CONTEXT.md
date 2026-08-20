# Linear Lite 统一语言

本文定义 Linear Lite 的业务词汇。Linear Lite 是以项目为一级上下文的轻量任务协作工具：项目成员围绕任务和项目文档协作，并可在任务上下文中发起本地 Pi 执行。

## 领域边界

Linear Lite 的业务范围是项目内的任务协作、项目文档、成员协作、通知、统计和任务关联的本地 Pi 执行。桌面端和移动端共享以下业务语言；Board、List、Gantt 等只是任务的不同呈现方式，不是不同的业务对象。

## 核心上下文

**Project（项目）**：任务、文档和成员共同归属的一级业务上下文。项目拥有名称和对外标识符；项目标识符也是任务编号的前缀。
_Avoid_: Workspace（工作区）作为项目的同义词、Team（团队）作为项目的同义词

**Project Identifier（项目标识符）**：项目在任务编号中的稳定短标识，例如 `ENG`。它不是项目名称，也不是任务的数据库内部编号。
_Avoid_: Issue ID、项目代码（除非明确指项目标识符）

**Workspace（工作区）**：产品界面的承载范围和导航容器，不是独立的业务归属对象。凡是表达任务、文档或成员的归属，使用 Project。
_Avoid_: 用 Workspace 表示 Project

**User（用户）**：可以登录产品并参与项目协作的人类账号。
_Avoid_: Account（账号，除非讨论认证资料）、Member（成员，除非强调项目成员关系）

**Project Member（项目成员）**：被纳入某个项目协作范围的用户。用户是全局身份，项目成员是用户与具体项目之间的协作关系。
_Avoid_: Project User、Team Member

**Project Invitation（项目邀请）**：邀请某个邮箱加入项目的待处理协作关系。接受邀请后才形成项目成员关系。
_Avoid_: User Invitation、Workspace Invitation

## 任务协作

**Task（任务）**：项目中可被描述、安排、跟踪和完成的工作单元。任务可以有标题、描述、状态、优先级、负责人、时间、进度、标签、评论和附件。
_Avoid_: Issue、Ticket、Work Item（对外业务语言统一使用 Task）

**Task Key（任务编号）**：任务对外使用的唯一编号，由项目标识符和项目内序号组成，例如 `ENG-1`。用户、链接和外部工具引用任务时使用任务编号。
_Avoid_: Issue ID、Task ID（泛指内部标识时除外）

**Parent Task（父任务）**：直接包含一个或多个子任务的任务。父任务本身仍然是可独立管理的任务。
_Avoid_: Epic（当前模型没有独立的 Epic 类型）、Container Task

**Subtask（子任务）**：直接隶属于父任务的任务。子任务与普通任务使用相同的字段和生命周期；“子”只描述层级关系。
_Avoid_: Sub-issue、Child Issue、Sub-Task（英文统一写 Subtask）

**Task Creator（任务创建者）**：创建任务的用户。创建者和任务负责人是两个独立角色。
_Avoid_: Owner（除非明确表示负责人）、Reporter

**Task Assignee（任务负责人）**：对任务承担业务责任的用户。负责人不等于创建者，也不表示任务必然由自动化执行。
_Avoid_: Owner、Executor、Operator

**Task Status（任务状态）**：描述任务当前生命周期阶段的状态。当前状态语言为 Backlog、Todo、In Progress、In Review、Done、Canceled 和 Duplicate；Done、Canceled、Duplicate 是终态。
_Avoid_: Stage、Column（Column 只表示看板中的呈现分组）

**Task Priority（任务优先级）**：表达任务处理紧迫程度的等级，当前为 Low、Medium、High、Urgent。
_Avoid_: Severity、Importance

**Due Date（截止日期）**：任务预计完成或应完成的日期。
_Avoid_: Deadline（产品字段统一称 Due Date）

**Planned Start Date（计划开始日期）**：任务计划开始的日期。它与 Due Date 共同描述任务的计划时间范围。
_Avoid_: Start Date（没有“计划”语义时不使用）

**Progress（完成进度）**：任务当前完成程度，以 0 到 100 的百分比表达。它不是任务状态的替代物。
_Avoid_: Completion Status、Percent Done（对外文案统一使用 Progress）

**Label（标签）**：项目级可复用的分类标记，可附加到多个任务。标签名称在项目内唯一。
_Avoid_: Tag、Category（除非讨论统计维度）

**Task Comment（任务评论）**：围绕单个任务发表的协作内容。评论可以是顶层评论，也可以回复另一条评论。
_Avoid_: Message、Note、Reply（Reply 只描述评论之间的关系）

**Comment Thread（评论串）**：一条顶层评论及其所有回复形成的讨论单元。
_Avoid_: Conversation（避免与本地 Pi 对话混淆）

**Mention（提及）**：在任务评论中明确指向一个用户的协作引用。提及可能触发该用户的站内通知。
_Avoid_: Tag、At User

**Task Attachment（任务附件）**：附属于任务的文件对象。文档附件属于 Project Document，不称为任务附件。
_Avoid_: Upload、File（仅表示存储对象时使用）

**Task Activity（任务活动）**：任务字段或收藏关系发生变化后形成的可追溯记录。评论不是活动，活动也不是评论。
_Avoid_: Log、History（History 专指文档版本历史）

**Favorite（收藏）**：用户对任务或项目文档建立的个人快捷访问关系。收藏不改变资源对其他用户的可见性。
_Avoid_: Bookmark、Starred（除非是图标名称）

## 项目文档

**Project Document（项目文档）**：归属于项目、可编辑并可组织成树的协作内容。文档与任务是两种不同的项目内容。
_Avoid_: Page、Wiki Page、Note、Doc（代码类型名可保留 ProjectDocument）

**Document Tree（文档树）**：通过父子关系组织项目文档的层级结构。移动文档改变其在文档树中的位置，不改变文档身份。
_Avoid_: Folder、Directory（文档不是文件系统目录）

**Document Revision（文档修订版）**：文档一次可恢复的标题和正文快照。修订版按文档版本递增，恢复修订版会产生新的当前版本。
_Avoid_: Document History、Snapshot（Snapshot 仅用于统计快照）

**Archived Document（已归档文档）**：不出现在默认活动文档树中的项目文档。归档不等于删除，恢复后重新回到活动文档树。
_Avoid_: Deleted Document、Hidden Document

**Project Content（项目内容）**：项目中的 Task 或 Project Document 的统称。项目级搜索和语义索引使用这一统称。
_Avoid_: Resource（过于泛化）、Item（过于泛化）

## 通知与统计

**In-App Notification（站内通知）**：发送给单个用户、用于提示其关注的任务协作事件的通知。通知有未读和已读状态。
_Avoid_: Alert、Message、Feed Item

**Analytics（项目分析）**：基于项目任务的创建、完成、截止、状态、负责人和优先级等事实形成的项目级统计视图。
_Avoid_: Dashboard（Dashboard 是页面容器，不是统计领域对象）、Report（除非是外发报告）

**Task Snapshot（任务统计快照）**：Analytics 在指定时间范围和指标口径下返回的任务明细集合。它不是对任务业务状态的复制。
_Avoid_: Task List（普通任务列表不一定有统计口径）

## 本地 Pi 执行

**Agent（Agent 主体）**：代表自动化执行能力的系统主体。Agent 不是人类用户，不是项目成员，也不是任务负责人。
_Avoid_: User、Assignee、Bot User（产品语言统一使用 Agent）

**Local Pi Execution（本地 Pi 执行）**：用户针对单个任务显式发起的一次本地自动化执行。它属于任务协作的执行动作，不改变任务负责人语义。
_Avoid_: Agent Task、Automatic Assignment、Background Job（泛指后台作业时除外）

**Execution Context（执行上下文）**：围绕一个任务建立的、可连续补充指令的本地 Pi 对话和工作目录关联。准备执行上下文不会提交一次执行。
_Avoid_: Session（Session 仅指 Pi 对话会话）、Workspace

**Turn（执行轮次）**：用户在同一个执行上下文中提交的一次指令及其对应的一轮执行请求。
_Avoid_: Message、Prompt（Prompt 只指发送给 Pi 的文本）

**Job（执行作业）**：一次 Turn 被提交后形成的可投递、可执行、可追踪的执行单元。
_Avoid_: Task（Job 不是业务任务）

**Bridge（本地 Bridge）**：运行在用户电脑上的本地连接进程，负责领取属于当前执行授权的 Job、启动 Pi 会话并回传执行状态。
_Avoid_: Agent、Runner、Worker（除非讨论通用后台组件）

**Execution Attachment（执行授权绑定）**：当前登录用户为执行上下文建立的一次性本地授权关系，用于让 Bridge 代表该用户领取对应 Job。
_Avoid_: Credential、Token、Agent Assignment

## 视图与呈现

**Task View（任务视图）**：展示同一组项目任务的界面方式。Board、List 和 Gantt 是任务视图，不改变任务本身。
_Avoid_: Board Task、List Task

**Board View（看板视图）**：按任务状态分组并以列呈现任务的任务视图。

**List View（列表视图）**：以列表或表格方式密集呈现任务及其字段的任务视图。

**Gantt View（甘特视图）**：以时间轴呈现任务计划时间和依赖关系的任务视图。

## 统一用法规则

- 业务文案、产品文档、接口说明和新代码命名优先使用本文件的英文术语及其中文译名。
- `Issue` 仅作为历史代码字段或外部系统原名保留；新业务概念统一写 `Task`。例如已有 `subIssueCount` 不改变其现有技术字段名，但文案写“子任务”。
- `Workspace` 仅描述界面容器；项目归属、权限、成员和内容范围统一以 `Project` 表达。
- “负责人”只表示 Task Assignee；本地 Pi 执行者使用 Agent/Bridge，不把自动化主体写成负责人。
- “文档”默认指 Project Document；只有讨论修订历史时才使用 Document Revision。
