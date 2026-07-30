# Outline → JLNX Wiki 子树批次迁移

## 批次输入

- 用户明确指定的一棵 Outline 子树。
- 一个在线模式 manifest，包含已有 state 映射节点和本批子树全部节点。
- 子树根节点的 Outline `urlId`。
- 独立的批次阻断报告路径，文件名使用子树 `urlId`，避免覆盖其他批次记录。

## 执行流程

1. 从 Outline 可见文档树清点整棵目标子树，固定记录 `outlineDocumentId`、标题、父级、`sortOrder` 和 `sourceUrl`。
2. 准备最小权限 Outline Key 与 Linear Lite JWT，分别写入权限为 `0600` 的认证文件。
3. 使用 catalog 编译器一次生成“state 全部已有节点 + 本批新节点”的完整 catalog；已有节点从目标文档树保留已验收布局，新节点按子树快照顺序追加。子树之外的 catalog 节点必须已经存在于迁移 state。
4. 迁移器最多并行读取 3 篇文档，对整棵子树完成创建前预检：
   - 标题与 manifest 完全一致；
   - 父级和既有目标映射一致；
   - Markdown 可转换；
   - 文档链接只走精确 Outline ID；
   - 附件文件名、大小、SHA-256 和响应头有效，单文件不超过 50 MiB。
   附件下载在整个批次内保持串行。
5. 被硬门禁阻断的节点不创建；依赖其父级或精确链接的节点同步阻断。所有阻断项原子写入本批 Markdown 报告，正常节点继续执行。网络超时、连接中断和 HTTP 5xx 属于临时故障，自动进行有界重试，耗尽后停止批次且不写入阻断报告。
6. 单写入器按父子关系与 `sortOrder` 连续创建文档，再串行上传预检通过的附件并更新正文。
7. 同一进程自动使用相同参数整批复跑；apply 与 verify 共享经过 SHA-256 校验的附件 spool，第二轮不重复下载附件，并确认没有新增文档、正文更新或附件上传。
8. 批次成功前自动回读目标文档树，逐项核验项目、父级和同级顺序。批次结果、附件级结构化事件日志和阻断报告统一写入权限为 `0700` 的运行目录；state 写锁确保只有一个写入器。
9. 整批只做一次 Chrome 验收，检查数量、顺序、层级以及代表性正文和附件下载。
10. 吊销临时 Outline Key 并清理临时凭据；保留权限为 `0600` 的 state 和批次运行记录。

## 命令

先将浏览器清点出的目标子树写入权限为 `0600` 的 `subtree-snapshot.json`。快照只需包含本批子树节点的 `outlineDocumentId`、`parentOutlineDocumentId` 和 `sortOrder`；标题与 `sourceUrl` 统一通过逐个 `documents.info` 获取：

```bash
npm run outline:catalog -- \
  --snapshot /path/to/subtree-snapshot.json \
  --state /tmp/outline-jlnx-api-pilot-state.json \
  --outline-auth-file /path/to/outline-auth.env \
  --target-auth-file /path/to/target-auth.env \
  --output /path/to/batch-catalog.json
```

编译器禁止 Outline export，也不按标题识别节点。已存在于 state 的节点只采用目标活动树中的父级与相对顺序，因此不会把已人工验收的目标布局重新改回 Outline 层级；仅快照中的新节点采用快照父级，并追加在已有受管同级节点之后。

再执行同步：

```bash
chmod 600 /path/to/outline-auth.env /path/to/target-auth.env

npm run outline:sync -- run \
  --catalog /path/to/batch-catalog.json \
  --subtree-root-id <outline-url-id> \
  --state /tmp/outline-jlnx-api-pilot-state.json \
  --outline-auth-file /path/to/outline-auth.env \
  --target-auth-file /path/to/target-auth.env
```

Outline 认证文件只读取一行 `OUTLINE_API_TOKEN=<临时 Key>`，目标端认证文件只读取一行 `JWT=<Linear Lite JWT>`。凭据不会写入事件日志或结果文件。未显式设置 `--run-dir` 时，运行记录写入 `docs/migrations/runs/<时间>-<项目>-<子树根>`。

运行目录中的 `attachment-spool` 仅保存本批附件及其摘要元数据：成功后自动删除；临时故障失败时保留，以便使用相同 `--run-dir` 续跑。spool 目录权限固定为 `0700`，文件权限固定为 `0600`。事件日志记录附件下载、缓存命中、上传和网络重试，但不记录认证信息与附件签名地址。

新入口只接受在线 API catalog 和 `--subtree-root-id`，不提供 collection/workspace export、标题匹配或字段回退。

## 阻断报告

运行目录中的 `blocked.md` 只记录创建前未通过硬门禁的节点，包含 Outline `urlId`、标题、父级、阻断代码、原因和源地址。处理阻断原因后，重新执行该节点所在的整棵子树批次，不做标题匹配、字段回退或单节点临时兼容。
