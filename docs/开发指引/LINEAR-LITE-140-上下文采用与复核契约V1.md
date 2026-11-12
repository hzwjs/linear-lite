# LINEAR-LITE-140 上下文采用与复核契约 V1

## 数据来源

网页项目文档由现有文档服务提供正文及版本。读取时正文与 `version` 取自同一文档快照；读取期间不持项目协调锁。项目文件内容由宿主读取，`contentVersion` 使用文件内容的 `sha256:` 摘要。

`contextRefs` 的来源字段如下，禁止混用：

- `DOCUMENT`：`kind`、`documentId`、`documentVersion`。
- `PROJECT_FILE`：`kind`、`projectPath`、`contentVersion`。

项目文件路径为规范化项目相对文件路径；内容版本格式为 `sha256:` 加 64 位小写十六进制。任务输入版本和文件修改时间不作为内容版本。

## 文档与采用操作

文档发现、读取、上下文采用使用当前批次、领取任务及执行尝试的归属。网页文档需要当前用户的项目成员权限；项目不符或文档不可用时拒绝。采用网页文档时在项目协调事务内核对项目、可用性和版本；版本变化时要求重读。

采用事实追加至当前 `attempt.checkpoints[].contextRefs`，不改变任务输入版本。相同操作标识和请求返回原回执；相同来源与版本的重复采用不追加事实。检查点与交付中的 `contextRefs` 引用当前尝试采用的来源版本；交付包含本次尝试各来源最后采用的版本。

文档变更事件针对消费旧版本的尝试追加 `CONTEXT_REVIEW_REQUIRED` 检查点，不触发任务停止。查询按来源版本和文档可用性生成 `CURRENT` 或 `REVIEW_REQUIRED`；本地文件缺少宿主核验事实时为 `PENDING_CONFIRMATION`，宿主提交的当前内容版本相符或变化时分别投影为 `CURRENT` 或 `REVIEW_REQUIRED`。

## 接线边界

服务端提供项目文档发现、读取、上下文采用和状态投影。宿主读取本地文件及内容版本的工具接线属于 LINEAR-LITE-146；本卡不提供宿主在线核验事实的接入口。网页任务详情展示属于 LINEAR-LITE-147；本卡提供 `contextFacts` 数据。前置成果复核与返工属于 LINEAR-LITE-142。
