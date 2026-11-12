# LINEAR-LITE-146 宿主行动链检查 V1

## 宿主协议

工具目录、参数校验和执行提示取自 [host-actions.mjs](../../codex-host/src/host-actions.mjs)。[App Server](../../codex-host/src/app-server.mjs) 校验工具调用所属的线程和回合，[宿主执行池](../../codex-host/src/pool.mjs) 校验领取、租约和工具权限。

| 工具动作 | 参数 | 执行与事实来源 |
| --- | --- | --- |
| `observe` | 空对象 | 取批次任务、关系、尝试和复核事实，按来源尝试及输入版本读取共享目录，携带当前事实刷新批次状态 |
| `claim` | `taskId`、`inputRevision` | 重查任务就绪状态，领取请求携带该次查询使用的前置观察，服务端固定来源交付 |
| `list_documents` / `read_document` | 空对象 / `documentId` | 按当前尝试权限读取网页项目文档及其版本 |
| `read_file` | `path` | 读取项目文件的内容、内容摘要和编辑版本 |
| `adopt_context` | `documentIds`、`projectPaths` | 选取本次尝试读取的材料。宿主从读取结果生成引用，校验文件版本，服务端校验文档版本 |
| `create_file` / `make_directory` | `path`、`operationId`，建文件另传 `content` | 通过项目级受管编辑执行 |
| `apply_patch` / `replace_file` | `path`、`version`、`operationId`，片段替换传 `oldText` 和 `newText`，全文替换传 `content` | 按读取版本执行，操作记录提供实际改动路径 |
| `remove_file` / `rename_file` | `version`、`operationId`，删除传 `path`，移动传 `from` 和 `to` | 按读取版本执行，操作记录提供实际改动路径 |
| `change_dependency` | `action`、`missing`、`reason`、`evidence`，增删关系指定 `prerequisiteTaskId`，申请新增目标指定 `goal` 和 `impact` | 记录 Agent 从成果发现的缺项，关系生效或需新增目标授权时收口当前尝试 |
| `retract_dependency` | `relationOperationId`、`missing`、`reason`、`evidence` | 本批等待错误关系时开放一次只读复查。原尝试确认停止后，宿主以原领取凭据和关系生效后的输入版本提交撤回，服务端复核归属和版本 |
| `checkpoint` | `artifactRefs`，可附 `finding` 和 `blockedReason` | 记录成果引用、发现和阻塞，宿主附本次尝试采用的上下文版本 |
| `review_prerequisite` | 来源任务、尝试、输入版本、`decision`、`finding`、`evidence`、`paths`，批次外返工需 `goal` 和 `impact` | 宿主从所选路径生成当前事实，服务端记录采用、目标内修补、等待变化或返工 |
| `deliver` | `conditions`、`artifactRefs` | 回合结束、写入封存且进程组退出后，宿主附操作路径、采用版本和交付前读取事实 |
| `stop` | `reason` | 请求批次停止，记录需要用户决定的原因 |

关系和复核回执中的 `stopRequested` 封存对应尝试，其他任务按批次预算和共享事实推进。错误关系撤回的原领取凭据限本次宿主批次进程使用，复查回合只有观察和撤回权限。宿主退出后的批次结束与凭据清理由既有恢复流程处理。

## 共享目录与历史快照

- 当前工作区提供文件和目录的内容版本。目录使用按相对路径排序的清单计算 SHA-256，文件名与目录名按尾斜线区分。能证实不存在的对象记录 `MISSING`，核验失败的路径保留待核验。
- 编辑、观察、快照和交付沿用批次开始时的物理根身份。换根触发停止；在共享目录改变的前置成果需要重读和复核。
- 历史快照保留当时的文件内容、目录、链接描述和删除记录。当前文件变化后，领取和复核依据当前事实。快照分块保存，宿主留存历史证据，项目目录由现有编辑工具写入。
- 文档和项目文件的首次采用是执行事实。观察会标出同设备绑定的已采用文件是否变化，使用实际读取版本判断。

## 协议检查

| 场景 | 结果 |
| --- | --- |
| 旧快照存在、当前来源变化或缺失 | 当前版本或缺失事实进入观察和领取，等待事实包含历史声明以外的复核路径 |
| 观察、领取、读取与采用、编辑、检查点、复核、交付 | 模拟 Agent 走通，首次采用后仍可编辑，交付包含宿主记录的路径和最新采用事实 |
| 关系变更、错误关系撤回、同批返工 | 旧尝试确认停止，撤回复查回合无领取和写入权，同批上游可领取返工 |
| 文件或物理根在提交后变化 | 交付前核验拦截旧证据，收口对应尝试或批次 |
| 凭据、历史快照、预算和空查询 | 凭据过滤、历史文件留存、32 MiB 流式快照、空查询和预算状态回归通过 |

`codex-host` 下执行 `rtk proxy node --test --test-timeout=30000 test/*.test.mjs`，124 项通过。服务端以 JDK 21 执行 `rtk proxy mvn -q -Dexec.skip=true -Dtest=ProjectArtifactPathServiceTest,TaskPrerequisiteReviewServiceTest,TaskCollaborationClaimServiceTest,TaskCollaborationDependencyServiceTest test`，44 项通过。

## 待办

受管可写命令及命令事实接入依赖待决的 LINEAR-LITE-144。普通命令沿用只读执行配置。本轮范围为代码及模拟协议核验，服务器部署、真实模型、定时任务和真实网页联调均未执行。
