# LINEAR-LITE-143 项目级受管编辑 V1

## 模块入口

`codex-host/src/executor.mjs` 导出 `createWriteGate`：

```js
const gate = await createWriteGate({
  projectDirectory, stateDirectory, attemptId, agentInstanceId,
  isAuthorized, knownCredentials,
})
```

`projectDirectory` 是经过规范化的项目根目录，`stateDirectory` 是存在的宿主私有状态目录；尝试标识和 Agent 标识用于关联事实。`isAuthorized()` 返回布尔值，租约失效或停止后返回 `false`。`knownCredentials` 包含宿主已知的执行凭据，路径、内容和操作标识不得包含这些值。

创建入口不接收任务写入范围。不同 gate 的项目物理目录设备号和 inode 相同时，共享操作级队列；队列覆盖读取条件检查与文件操作，不跨模型回合、命令或任务保留路径占有权。

## 编辑接口

接口参数均为对象，文件内容采用 UTF-8 文本。`operationId` 为同一尝试内不重复的 1 至 128 位字母、数字、下划线或连字符。

| 方法 | 参数 | 行为 |
|---|---|---|
| `readFile` | `{ path }` | 返回 `{ path, content, contentVersion, version }` |
| `createFile` | `{ operationId, path, content }` | 文件不存在时创建，不覆盖现有文件 |
| `applyPatch` | `{ operationId, path, version, oldText, newText }` | 核对读取令牌及唯一原文上下文后替换 |
| `replaceFile` | `{ operationId, path, version, content }` | 核对读取令牌后替换整份文本 |
| `removeFile` | `{ operationId, path, version }` | 核对读取令牌后删除普通文件 |
| `renameFile` | `{ operationId, from, to, version }` | 核对来源令牌，在目标不存在时移动文件 |
| `makeDirectory` | `{ operationId, path }` | 目录不存在时创建 |
| `sealAndDrain` | 无 | 拒绝本尝试的新操作，等待本尝试在途操作结束 |
| `changedPaths` / `operationFacts` | 无 | 返回本尝试已确认的路径变化和操作事实 |

`contentVersion` 是实际读取字节的 `sha256:<摘要>`，供本地上下文采用记录使用。`version` 是含内容摘要、设备号和 inode 的不透明编辑令牌，写操作原样传回；禁止解析该令牌作为内容引用，也不在两字段之间回退。相同内容的文件被替换为不同 inode 时，内容版本不变，编辑令牌变化。

读取期间文件身份、大小或时间信息变化时返回冲突；时间信息用于检测并发读取变化，不作为内容版本。补丁上下文不存在或出现多次时返回冲突。全文件替换通过同目录独占临时文件及原子替换执行，复制 ACL 和扩展属性并校验所有者、用户组和权限位。源文件禁止写入、权限复制失败或元数据不匹配时拒绝替换。文件移动使用目标不存在的创建语义；中途失败时记录结果待确认。

## 项目边界与操作记录

每个文件操作在独立 macOS `sandbox-exec` helper 中运行：禁止网络，仅允许执行受信 Node 和固定 `/bin/cp`，放行项目读写、helper 与运行时所需读取，拒绝宿主私有状态读取和写入。源文件通过已核验身份的描述符传给 `cp -p`，临时文件使用独占描述符；复制后恢复 POSIX 权限位，关闭描述符并按复制后的 ACL 重开写入。工具不接受用户提供的命令或参数。子进程环境固定为受控值，`NODE_OPTIONS`、`NODE_PATH` 和动态库注入环境变量不继承；输入和输出经管道传递。helper 与 Node 的实际路径须位于可写项目和宿主私有状态目录之外。安装版须从受信宿主应用包提供 helper 与运行时。

项目物理根身份、精确名称、符号链接及硬链接在 helper 内检查。项目外路径、宿主私有状态、`.git` 及已知凭据内容被拒绝。不同 gate 的一致性保证覆盖该宿主进程的受管编辑入口；外部编辑器和可写命令不纳入该保证。

每次修改在 `stateDirectory/file-operations/<attemptId>/<operationId>.json` 创建 `pending` 记录，发送修改输入前写入 `processGroupId`，确认结果后写入 `completed` 或 `rejected`；结果不明确时保留 `pending` 或 `unknown`，封存本尝试。事实包含尝试、Agent、操作、进程组、时间和实际变化路径，不保存文件内容或凭据。重复操作标识遭拒，禁止以新操作推断上一次是否成功。

临时文件清理失败时，操作标记 `unknown`，记录 `cleanupPaths` 项目相对路径并封存该尝试；其他尝试不受该封存影响。恢复流程须复核记录和文件状态，不得推断目标文件已完成变更。

单次 helper I/O 截止时间为 30 秒。到时向 helper 所在进程组发送 `SIGKILL`；正常或异常关闭均检查进程组，对残留进程执行收口，确认进程组消失后释放项目操作队列。进程组状态无法确认时保持等待；修改结果不明时保留记录并封存本尝试。该截止时间不代表任务执行时长或路径租约。

当前实现依赖 macOS `sandbox-exec`，不支持的平台返回能力错误，不回退到无沙箱执行。版本检查与单次实际编辑保证适用于同一宿主的受管入口；主动脱管的外部进程及宿主运行期之外的变化需要由后续恢复和复核流程处理。

## 后续接线

LINEAR-LITE-146 负责在 `pool.mjs` 和宿主工具协议中接入新方法、向 gate 传入执行尝试和操作标识，并消费 `contentVersion` 和操作事实。旧 `writeFile(path, content)` 接口不保留；本卡不修改 pool 的旧调用，旧执行链在接线完成前不可用。

LINEAR-LITE-145 负责按批次和代次处理停止恢复；后续接线须检查 `file-operations` 中的 `pending` / `unknown` 记录，结果不明时不伪造成功。LINEAR-LITE-148 负责安装包及服务器分发资源核对，确保受信 bundle 包含 `executor-worker.mjs` 和 Node 运行时。
