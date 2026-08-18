# Linear Lite 领域词汇

## User（真实用户）

系统中的人类账号。任务负责人决定是否把某个任务交给自己电脑上的本地 Pi 处理。

## Task Assignee（任务负责人）

`tasks.assignee_id` 指向的真实用户。负责人表示业务责任，不表示任务一定会被自动化执行。

## Local Pi Execution（本地 Pi 执行）

负责人针对单个任务显式准备并提交的一组本地自动化执行。它是任务负责人之外的执行行为，不是任务负责人身份。

## Execution Context（执行上下文）

一个任务的一组连续 Pi 对话和工作目录身份，由 `execution_id`、`session_id` 和工作区绑定共同确定。上下文准备好不代表已经执行。

## Turn（执行轮次）

负责人在执行上下文中提交的一次补充指令。每次 Turn 创建一个 Job；多个 Turn 复用同一个 Execution Context。

## Execution Attachment（执行授权绑定）

由已登录浏览器为当前 Execution Context 建立的一次性本机内存绑定。它只在当前执行期间关联负责人和 Bridge，不落盘、不展示、不参与任务负责人语义。

## Bridge

用户电脑上的常驻本地进程。Bridge 只领取当前执行授权绑定对应的本地 Pi Job，启动对应 Pi RPC 会话，并回写执行状态。

## Job（执行作业）

一次 Turn 的持久化投递单元。只有负责人提交补充指令后才创建 Job；准备上下文不会创建 Job。
