# LINEAR-LITE-143 项目级受管编辑核验 V1

日期：2026-11-10。执行环境：macOS、本机 Node 22.23.4。范围：宿主编辑模块与隔离临时项目，不涉及现有运行中的宿主。

## 改动

- `codex-host/src/executor.mjs`：删除任务范围门禁，提供尝试级 gate、同物理根的操作级队列、操作事实和超时收口。
- `codex-host/src/executor-worker.mjs`：在 macOS 项目沙箱执行文本读取、创建、带版本的局部编辑、替换、删除、重命名与目录创建。
- `codex-host/test/executor.test.mjs`：覆盖项目编辑、同文件竞争、版本及物理对象冲突、凭据和路径边界、超时封存、进程退出及操作事实。

`version` 用于编辑提交时比较内容与物理身份，`contentVersion` 表达读取内容；两种事实使用不同字段。更换成相同内容的不同 inode 时，旧编辑令牌被拒绝，内容版本保持一致。

## 本卡检查

`rtk proxy node --test codex-host/test/executor.test.mjs codex-host/test/executor-acceptance.test.mjs`：22 项通过、0 项失败、0 项跳过。覆盖真实子目录及项目根目录在物理目录与项目外符号链接间交换的竞争探针，项目外文件保持原状；该竞争测试是本机时序采样。

非模型假 helper 配合模拟时钟验证：超时向进程组发送 `SIGKILL`；helper 关闭且进程组有残留时，同根后续操作及 `sealAndDrain` 保持等待，向残留组发送 `SIGTERM`，确认组消失后释放队列。修改记录保持 `pending` 并记录进程组，gate 封存。另有只读操作超时封存检查。Node 提示 MockTimers 为实验 API。

固定 cp 与文件描述符沙箱规则下，受控锚点时序的实际输出及程序：[沙箱锚点探针输出](证据/20261110-LINEAR-LITE-143-沙箱锚点探针V2.txt)、[探针程序](证据/20261110-LINEAR-LITE-143-沙箱锚点探针V2.cjs)。

独立验收覆盖 ACL、扩展属性与可执行权限保留，禁止写 ACL 拒绝替换；禁止删除 ACL 导致临时文件清理失败时，记录 `unknown` 和 `cleanupPaths`，源文件保持原文，其他 gate 可读取。固定 fd 的 `cp -p` 及 mode 恢复探针保留 ACL 顺序和扩展属性：[探针输出](证据/20261110-LINEAR-LITE-143-元数据探针V1.txt)、[探针程序](证据/20261110-LINEAR-LITE-143-元数据探针V1.cjs)。

`rtk proxy node --check codex-host/src/executor.mjs` 与 `rtk proxy node --check codex-host/src/executor-worker.mjs` 通过。

## 边界

受管编辑仅支持 macOS sandbox-exec；不支持平台返回能力错误。共享操作一致性覆盖宿主进程内的受管编辑入口；外部编辑器、可写命令和主动脱离进程组的进程不属于该保证。模块支持 UTF-8 文本，不对非 UTF-8 文件进行转换写入。

本卡不接入 `pool.mjs`；旧 `writeFile(path, content)` 执行链在 LINEAR-LITE-146 接线前不可用。安装资源由 LINEAR-LITE-148 核对；历史运行中的本机宿主没有在本卡重启或替换。E2E 未执行：用户未显式调用；真实模型、部署和定时任务保持关闭。
