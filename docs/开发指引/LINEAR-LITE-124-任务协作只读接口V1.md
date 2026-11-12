# LINEAR-LITE-124 任务协作只读接口 V1

## 读取与权限

`POST /api/collaboration/tasks/state` 使用网页登录认证，请求体为：

```json
{"taskKey":"项目任务编号","inputRevision":1}
```

`inputRevision` 来自 `POST /api/collaboration/inputs/get`。后者是前端协作编辑字段的读取入口；状态接口不复制授权、范围、公共约定或依赖配置。保存沿用 `/api/collaboration/inputs/replace`。

服务端检查任务访问权限，持有项目协调锁，锁定任务并复核项目成员与输入版本。版本不符返回 HTTP 409，页面保留编辑内容。状态读取不调用具有停止副作用的宿主观察方法，不取得设备授权锁，不写入执行状态或停止事实。

## 响应事实

响应数据位于 `data`，设置 `Cache-Control: no-store` 与 `X-Linear-Lite-Server-Time`。

| 字段 | 来源与用途 |
|---|---|
| `serverTime` | 服务端读取时刻，绝对时间 |
| `status/progressPercent/completedAt/updatedAt` | 通过输入版本校验后的同一任务行 |
| `configurationReady/configurationWaitingReason` | 现有输入配置规则的计算结果 |
| `activeOrFrozen` | 当前任务存在未确认停止的执行尝试 |
| `attempts` | 当前任务的执行历史，复用 `TaskExecutionAttemptResponse`，不返回领取凭据摘要 |
| `attemptBindings` | 历史执行关联的同项目绑定标识与设备标识，不返回其他成员的本机目录 |
| `bindings` | 当前用户同项目有效绑定的输入就绪、范围阻塞、宿主成果检查与前置成果引用 |

输入就绪不代表任务获得授权、处于可领取业务状态、宿主在线或本机文件可用。`requiresHostArtifactCheck` 表示宿主需核对成果，服务端不推断磁盘事实。

任务租约沿 `CollaborationLeaseTime.toInstant` 输出绝对时间。页面结合本次 `serverTime` 展示到期后的待恢复状态；租约到期和浏览器超时不构成进程退出或停止确认。

## 网页状态同步

协作保存完成后，通过 `inputs/get → state(inputRevision)` 取得同一版本的输入与业务状态，形成协作影响字段组；项目任务列表提供受影响下游的同源字段。较高输入版本进入现有保存队列的确认基线、列表、缓存和项目快照，不清除正文或标题草稿。迟到的较低版本普通保存响应不能恢复旧输入和完成状态。

宿主交付可能在同一输入版本内改变业务状态。轮询应用此类观测时，核对请求前后的行、缓存和保存队列引用，以及用户的完成字段编辑；存在并发变化时丢弃观测，等待下一次轮询，不使用秒级更新时间排序或通用自动重试。

普通保存失败沿用错误反馈与持久草稿，不能将失败描述成保存成功。只读进度字段沿数据库非空 `tasks.progress_percent`、响应 `progressPercent` 与前端消费传递，不使用缺失值回退。
