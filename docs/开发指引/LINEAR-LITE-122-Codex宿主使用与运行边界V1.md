# LINEAR-LITE-122 Codex 宿主使用与运行边界 V1

## 启动本机身份服务

在运行 Linear Lite 的用户设备执行：

```sh
node codex-host/src/cli.mjs serve --origin https://linear.example
```

`--origin` 填用户访问 Linear Lite 的站点 origin。服务监听 `127.0.0.1:9781`，仅接受该来源的身份与目录校验请求。`--state-dir` 可指定宿主状态目录；默认目录位于当前用户的 `Library/Application Support/Linear-Lite/CodexHost`。设备标识保存在状态目录，项目目录来自用户的服务端绑定。服务不启动执行批次或定时任务。

## 执行批次

`run` 接受 `--base-url`、`--project-id`、`--task-ids`（逗号分隔的任务数字 ID）、`--slots`、`--max-attempts`、`--max-duration-ms` 和 `--expires-at`（UTC 时间点）；认证凭据通过标准输入提供。宿主通过绑定读取项目根目录，并按物理目录持锁。Agent 从共享环境选择任务；服务端裁决领取和范围。宿主持有批次与领取凭据，Agent 通过动态工具读取环境、领取、修改文件、记录事实与提交成果。写入仅支持 UTF-8 文本文件、目录创建和普通文件删除；Git 数据不在 Agent 写入范围。

仅 macOS 的 Codex App Server 只读沙箱及宿主外层目录写入限制经过本机探针。宿主按配置关闭 MCP、应用、插件、hook、子代理等能力并验证运行清单；不满足边界时拒绝启动。受限范围内，宿主在租约有效时执行文件修改，停止时封存写入能力并等待在途写入；只有对应回合结束且宿主完成停止流程，才向服务端确认停止。异常保留恢复关联和物理目录锁；后续恢复须核实旧宿主退出并取得服务端停止确认。

成果检查点在宿主私有目录保存文件内容、目录和删除记录。下游声明可用前，宿主核验快照和共享目录内容。宿主不执行 Git add 或 commit。该执行方式使用 Codex 线程，不能称为 Codex 原生 subagent。

## 定时计划

```sh
node codex-host/src/cli.mjs plan
```

输出计划为 `0 1 * * *`、`Asia/Shanghai`，`enabled` 为 `false`。该命令不注册系统任务。

## 验证范围

无模型探针覆盖 App Server 初始化、只读线程及动态工具注册、MCP 禁用、项目目录及临时目录写入约束。单元测试使用模拟协议和服务响应，覆盖领取、写入、快照、停止恢复及本机身份服务入口。尚未执行实际模型业务批次，不能将这些结果作为真实任务完成证据。
