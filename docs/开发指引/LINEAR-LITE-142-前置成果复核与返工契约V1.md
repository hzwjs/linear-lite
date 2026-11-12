# LINEAR-LITE-142 前置成果复核与返工契约 V1

前置任务存在同绑定、当前输入版本、停止确认的成功交付时，下游领取后复核该交付；项目文件与旧检查点不同、产物缺失或缺少宿主核验不构成领取障碍。没有成功交付或来源输入版本失效时保持依赖等待。

## 复核事实

`PrerequisiteReviewRequest` 包含 `sourceTaskId`、`sourceAttemptId`、`sourceInputRevision`、`decision`、`finding`、`evidence`、`observations`、`goal` 和 `impact`。`observations` 记录当前目标选择的相关读取，不把历史 `artifactRefs` 变为必读清单；空数组表示采用交付说明，不证明本机产物有效。`ArtifactObservation` 使用 `path`、`state` 和 `contentVersion`，`PRESENT` 的内容版本为宿主读取内容的 `sha256:` 摘要，`MISSING` 没有内容版本。路径是规范化项目相对路径，目录以 `/` 结尾。目录 `PRESENT` 版本由宿主计算规范递归清单的 SHA-256：相对路径排序并记录类型、路径及文件内容版本，空目录有确定表示，不跟随出界符号链接；`MISSING` 没有版本。规范清单的实际读取与核验由 LINEAR-LITE-146 实施；本服务只校验路径和版本格式，不以 mtime 或任务输入版本代替内容版本。

复核由有效批次内的下游尝试发起。项目协调事务核对调用尝试、前置关系、同绑定来源、来源输入版本、停止确认和领取时来源快照；来源已替换时拒绝旧复核。每次复核在检查点写入 `PREREQUISITE_REVIEW` 和来源、观察、结论、依据，操作回执记录幂等结果；相同请求重放原回执，不改写历史交付。

- `ADOPT`：当前读取事实满足本任务要求，记录采用依据。
- `REPAIR_IN_SCOPE`：记录发现，Agent 在当前授权目标内修复，再读取及采用。
- `WAIT_FOR_CHANGE`：记录具体来源、读取事实与依据并请求停止；相同事实保持等待，来源或相关观察变化后重评。
- `REQUEST_REWORK`：来源任务在原批次集合时，领域事务使来源及下游输入失效，来源恢复 `todo`，未确认的旧尝试进入停止流程。来源成功交付原文保留。
- 批次外来源需要返工时，响应 `GOAL_AUTHORIZATION_REQUIRED` 并记录业务目标、影响与停止意图；当前目标内的工作由 Agent 在本任务处理，新增业务目标经用户授权后进入新批次。原批次任务集合不扩张。

## 阻塞与完成

观察入口提供 `PrerequisiteSourceObservation` 数组，元素包含 `sourceAttemptId`、`sourceInputRevision` 和宿主当前 `observations`。无当前读取事实可传空数组；首次观察可领取。批次外返工形成具体阻塞后，原批次、下游输入版本及来源交付相同且缺少当前观察时保持等待；当前观察覆盖阻塞相关路径且存在内容版本或存在状态变化时恢复候选。新增无关路径、遗漏已观察路径和时间经过不解除阻塞。新来源交付、新批次或业务输入变化触发重评；预算和截止时间沿批次原事实。

状态由 `PrerequisiteReviewStatus` 唯一投影：未采用或来源不符为 `REVIEW_REQUIRED`；已采用材料有已知变化为 `REVIEW_REQUIRED`，缺少覆盖为 `PENDING_CONFIRMATION`，全部采用材料的读取事实与当前核验一致为 `CURRENT`；仅采用交付说明为 `NOT_APPLICABLE`，不表示本机产物有效。`CURRENT` 表示读取事实一致，不表示 `MISSING` 的产物存在。

完成证据包含 `prerequisiteAdoptions` 数组，元素为 `reviewOperationId` 与交付前宿主 `observations`。每个前置交付须对应本次尝试最后一条 `ADOPT` 事实；来源尝试与版本须和领取快照、当前前置一致，交付前读取事实须与采用事实相同。来源替换、缺少采用、采用后出现新的修复结论或内容变化时拒绝旧证据。没有前置交付时使用空数组；只读任务允许空 `changedPaths`。

宿主真实文件读取、受管工具调用、完成前读取供证和调度接线由 LINEAR-LITE-146 承担。服务器校验领取归属、来源和声明事实，不声称读取本机文件；前端展示由 LINEAR-LITE-147 承担。
