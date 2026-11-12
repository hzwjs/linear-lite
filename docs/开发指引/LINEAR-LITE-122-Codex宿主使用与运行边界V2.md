# LINEAR-LITE-122 Codex 宿主使用与运行边界 V2

日期：2026-11-10。本版本替代 V1 的执行边界说明，V1 留作历史记录。

## 本机身份与项目绑定

```sh
node codex-host/src/cli.mjs serve --origin https://linearlite.eobua.cn
```

身份服务监听 `127.0.0.1:9781`，接受指定站点来源的身份读取与目录校验。设备标识保存在当前用户的 `Library/Application Support/Linear-Lite/CodexHost/device.json`，每个宿主进程生成本次实例标识。服务端的认证用户、项目、设备确定一条目录绑定；项目根目录是唯一维护项，资料目录从根目录派生 `docs`。

该入口不启动模型批次或注册定时任务。

## 真实模型执行

`run` 入口使用服务端 origin、项目 ID、任务数字 ID、槽位、尝试数、时长与 UTC 到期时间参数，认证凭据从标准输入读取。宿主读取固定绑定、持有物理目录锁、签发批次，Agent 通过共享环境选择任务。Agent 使用 App Server 线程，不是 Codex 原生 subagent。

本地 Code Mode host 是动态工具派发所需的基础设施，宿主启动时显式启用并核验，不配置远程 Code Mode endpoint。MCP、应用、插件、hook、浏览器、原生多 Agent 等能力保持关闭；MCP 清单必须为禁用且工具数为 0，否则拒绝启动线程。

配置、线程和回合使用 Codex 原生 `read-only`，审批为 `never`，命令网络访问为 `false`。macOS 使用原生 Seatbelt；不在 App Server 外层嵌套 `sandbox-exec`。双层组合会导致读取控制命令出现 `sandbox_apply: Operation not permitted`，不能以该失败作为拒写证据。权限组合依据 [Codex 官方安全契约](https://learn.chatgpt.com/docs/agent-approvals-security)，动态工具协议依据 [App Server 文档](https://learn.chatgpt.com/docs/app-server)。

普通命令读取共享项目文件，文件变更通过 `host_action` 的宿主写入门完成。动态调用核验工具名、线程与回合归属；写入核验租约和持有范围，拒绝 Git 数据、宿主私有路径、符号链接、硬链接与名称别名。变更支持 UTF-8 文本写入、创建目录和删除普通文件。

`checkpoint` 的 `artifactRefs` 为字符串数组；可选的 `finding`、`blockedReason` 须为字符串，不需要时省略。`null` 在本机返回字段修正提示，不提交到服务端；模型可修正后重试。其他错误沿用固定拒绝提示，不向模型返回凭据或内部异常内容。

## 交付、停止与恢复

宿主续租并使用服务端响应时钟计算单调截止点。停止、失联或输入失效后，封存写入能力并排空在途写入；对应回合结束后提交停止确认。失败时保留恢复关联和物理目录锁，恢复先核实原宿主退出，再取得服务端尝试与停止事实。

交付记录包含条件、修改位置、成果引用和内容快照。下游领取前，宿主核验前置快照与共享目录内容。快照保存在宿主私有状态目录，可恢复交付时的文件；宿主不执行 Git add/commit。

正常终止返回 `COMPLETE`；依赖/资源等待、预算耗尽、用户停止、业务决策与恢复待处理分别返回对应状态。空查询不等于完成。

## 原生证据与计划

同一配置下的无模型探针验证：读取命令成功，项目与临时目录写入、脱管子进程写入、硬链接改写和命令网络访问被拒绝；证据见 [只读执行探针 V3](../验收/真实模型批次/20261110-01/本地工具宿主边界探针V3.json) 和 [硬链接与网络探针 V4](../验收/真实模型批次/20261110-01/本地工具宿主边界探针V4.json)。这些探针不等于对全部原生工具实现的逐项审计。

```sh
node codex-host/src/cli.mjs plan
```

该命令输出 `0 1 * * *`、`Asia/Shanghai`、`enabled=false`，不注册系统任务。真实模型验收结果见本轮验收文档，不能把开发单元测试或只读探针作为业务批次完成证据。
