# Pi Bridge macOS 常驻方案

## 1. 方案结论

Pi Bridge 统一安装为当前 macOS 用户的 `launchd LaunchAgent`，由 `launchd` 在用户登录后启动、异常退出后自动拉起。生产运行不再依赖终端、Codex、IDE 或 `npm start` 的生命周期。

Bridge 自身负责后端重连、任务租约和 Pi 子进程收敛；`launchd` 只负责 Bridge 进程存活。两层职责固定，不引入 PM2、Docker 或第二套守护路径。

## 2. 当前断连结论

当前 Bridge 的运行链路是：

```text
ChatGPT -> Codex -> rtk -> npm start -> node src/index.mjs
```

仓库没有 LaunchAgent 或其他进程守护配置。宿主应用、任务终端或 Node 进程退出后，`127.0.0.1:9780` 随即消失，前端健康检查将 Pi 判为不可用。

另外两处会放大断连感知：

- 前端打开负责人选择器时只执行一次 800ms 健康检查，短时唤醒或系统繁忙也会被判为离线；
- Bridge 的单个 API 请求会无限重试，并在首次请求时固定地址和凭据，连接配置更新后旧请求不会切换到新配置。

## 3. 常驻进程模型

固定使用用户级服务：

```text
launchd gui/<uid>
  -> com.linearlite.pi-bridge
      -> Bridge 主进程
          -> 本地配置 HTTP 服务 127.0.0.1:9780
          -> Linear Lite 任务轮询与心跳
          -> 当前 Job 对应的 Pi RPC 子进程
```

LaunchAgent 使用以下固定策略：

- `Label`: `com.linearlite.pi-bridge`；
- `KeepAlive`: `true`，Bridge 退出后由系统重新拉起；
- `ThrottleInterval`: `10`，避免启动失败形成热循环；
- `ProcessType`: `Background`；
- `ExitTimeOut`: `15`，给 Bridge 留出子进程清理时间；
- `ProgramArguments` 直接使用安装时解析出的 Node 绝对路径和 Bridge 入口，不经过 shell、npm、nvm 初始化脚本；
- `WorkingDirectory` 指向稳定安装目录，不指向开发仓库；
- 标准输出和错误输出写入固定日志目录。

LaunchAgent 文件固定为：

```text
~/Library/LaunchAgents/com.linearlite.pi-bridge.plist
```

服务管理只提供一套命令语义：

```text
install   安装 Bridge 文件并 bootstrap LaunchAgent
start     kickstart 已安装的 LaunchAgent
restart   kickstart -k 已安装的 LaunchAgent
status    输出 launchd 状态和 Bridge 运行状态
logs      输出固定日志文件位置
uninstall bootout LaunchAgent 后删除程序文件和 plist
```

## 4. 安装目录与运行数据

程序、配置、会话和日志使用唯一固定路径：

```text
~/Library/Application Support/Linear Lite/Pi Bridge/
  app/                 # 安装后的 Bridge 程序
  data/settings.json   # Linear Lite 地址和 Bridge Credential
  data/projects.json   # projectId -> 本地目录
  sessions/            # executionId 对应的 Pi session
  runtime/             # pid、实例状态和当前 Job 运行记录

~/Library/Logs/Linear Lite/
  pi-bridge.out.log
  pi-bridge.err.log
```

安装命令将 `pi-bridge` 程序原子复制到 `app`，再生成 plist 并启动服务。Bridge 只读取上述路径，不再读取仓库内 `.pi-bridge`、相对路径或环境文件，不保留多路径回退。

`settings.json` 权限固定为 `0600`，数据目录固定为 `0700`。Bridge Credential 只保存在本机，不写入 plist、命令行参数和日志；凭据通过已登录用户的短时配对流程生成，不手工复制 Agent Token。

## 5. Bridge 生命周期

Bridge 主进程改为显式生命周期状态机：

```text
starting -> config_required -> connecting -> online
                                ^            |
                                |            v
                                +-- degraded-+

任意运行态 -> stopping -> exited
```

- `starting`：占用本地端口、加载唯一配置路径并初始化 session 目录；
- `config_required`：未完成地址或 Bridge Credential 配对，只保留配置服务，不领取任务；
- `connecting`：尝试连接 Linear Lite；
- `online`：最近一次 Bridge API 请求成功，正常领取和执行显式 Job；
- `degraded`：网络、超时或服务端 5xx，Bridge 仍存活并持续重连；
- `stopping`：停止领取任务，终止定时器，向当前 Pi 发送 `SIGTERM`，关闭本地 HTTP 服务后退出。

Bridge 监听 `SIGTERM`、`SIGINT` 和 `SIGHUP`，所有退出路径统一进入 `stopping`。Pi RPC 始终是 Bridge 的受管子进程；Bridge 主动退出时不得留下孤儿 Pi 进程。

同一用户只允许 LaunchAgent 启动一个 Bridge。`127.0.0.1:9780` 绑定失败视为重复实例，进程明确退出，由 `launchd` 节流，不启动第二条轮询链路。

## 6. 后端连接与自动恢复

API 客户端只负责“一次有超时的请求”，不在单个请求内部无限循环。运行时调度器统一处理重连：

1. 每次请求前重新读取当前 `settings.json`；
2. 网络错误、10 秒超时和 5xx 进入 `degraded`；
3. 按 `1s -> 2s -> 4s -> 8s -> 16s -> 30s` 上限退避并加入随机抖动；
4. 任一 Agent API 成功后立即回到 `online` 并重置退避；
5. 4xx 视为确定性配置或权限错误，进入 `config_required`，不执行无意义重试；
6. 配对或凭据轮换事件立即中断旧等待，以新地址和 Bridge Credential 发起连接。

执行中的 Job 遇到网络中断时保留 Pi 进程和执行上下文。心跳、事件和最终结果继续使用现有 `executionId + jobId` 唯一链路，恢复网络后按原顺序提交；不得创建新 Job、切换 session 或读取其他字段补偿。

Bridge 异常退出后由 `launchd` 拉起。新进程先恢复本机记录的当前 Job，再进入新 Job 领取循环；服务端租约仍是任务所有权的最终判定，恢复失败时等待原租约到期后认领同一个 Job，不创建替代执行记录。

## 7. 健康检查与前端状态

`GET /healthz` 返回 Bridge 真实运行状态：

```json
{
  "status": "online",
  "configured": true,
  "backendReachable": true,
  "version": "<bridge-version>",
  "startedAt": "<ISO-8601>",
  "lastConnectedAt": "<ISO-8601>"
}
```

`status` 只允许 `config_required | connecting | online | degraded | stopping`。配置页和前端都读取这一结构，不再用单个 `{ "status": "ok" }` 掩盖后端断连。

前端健康检查超时统一为 2 秒：

- 请求失败：显示“本地 Bridge 未运行”；
- `config_required`：显示“Bridge 待配置”；
- `connecting/degraded`：保留 Pi 入口并显示“Bridge 正在重连”；
- `online`：正常开放 Pi 操作。

短时后端不可达只改变连接状态，不把仍存活的 Bridge 从负责人和评论入口中移除。

## 8. 实施范围

1. 新增 Bridge 服务管理脚本，完成程序安装、plist 生成和 `launchctl bootstrap/bootout/kickstart`；
2. 将 Bridge 配置、项目映射、session 和运行数据收敛到固定用户目录；
3. 将 `main` 无限循环重构为可停止的 Bridge 生命周期状态机；
4. 将 API 无限重试收敛为单次请求，由统一连接调度器负责退避和配置热切换；
5. 增加统一信号处理和 Pi 子进程清理；
6. 扩展 `/healthz`，前端按真实状态展示“未运行、待配置、重连中、在线”；
7. 在项目使用说明中删除以终端 `npm start` 作为正式启动方式，统一改为服务安装命令。

## 9. 完成标准

- 用户登录后无需打开终端、Codex 或 IDE，Bridge 自动运行；
- Bridge 异常退出后由 `launchd` 自动拉起，关闭宿主应用不再造成断连；
- Mac 睡眠唤醒、短时断网和 Linear Lite 重启后，Bridge 自动恢复连接；
- 更新地址或重新配对 Bridge Credential 后立即使用新配置，不需要重启 Bridge；
- Bridge 停止或更新时不遗留 Pi 子进程；
- 前端能区分 Bridge 未运行、待配置、重连中和在线，不再把瞬时后端断连显示为本地 Bridge 消失；
- Bridge 只存在一套启动入口、一套配置路径和一条任务恢复路径。
