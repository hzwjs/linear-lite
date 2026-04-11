# Frontend Code Review - 2026-04-11

范围：`src/` 前端实现，重点检查状态管理、路由鉴权、任务详情交互、全局事件监听和构建性能。

## 结论

这版前端代码整体可运行，测试和构建都通过，但存在几处会影响数据一致性、任务详情稳定性和长期运行可靠性的缺陷。优先处理 P1 问题，再补 P2 的资源清理和实时连接问题。

## Findings

### P1 - 乐观更新失败后不回滚，UI 和服务端可能长期不一致

- 定位：`src/store/taskStore.ts:256-294`
- 现象：`updateTask()` 先执行 `applyLocalTaskPatch()`，随后发起请求；如果请求失败，只会写入 `error.value` 并抛错，不会恢复本地任务快照。
- 影响：任务状态、负责人、日期、标签等字段可能在本地看似已保存，但后端实际未成功，用户会继续基于脏状态操作。
- 建议：保存 patch 前保留前态，失败时回滚，或者改成“请求成功后再提交本地状态”的保守策略。

### P1 - 任务详情的异步加载没有竞态保护，快速切换会串数据

- 定位：`src/components/TaskEditor.vue:299-358`, `src/components/TaskEditor.vue:456-550`
- 现象：`loadSubIssues()`、`loadActivities()`、`loadComments()`、`loadAttachments()` 都是独立异步请求，没有 task id / request id 校验。
- 影响：用户在两个任务间快速切换时，晚到响应可能覆盖当前任务的子任务、评论或附件列表。
- 建议：给每次加载绑定当前 `task.id`，返回时校验是否仍然匹配；必要时使用递增请求序号或 `AbortController`。

### P2 - NotificationCenter 的全局点击监听缺少卸载兜底

- 定位：`src/components/NotificationCenter.vue:15-32`
- 现象：`watch(open)` 在弹层打开时挂载 `document.click` 监听，但组件卸载时没有 `onUnmounted()` 清理。
- 影响：如果组件在弹层打开状态被卸载，会残留全局监听，属于资源泄漏和潜在的异常行为。
- 建议：增加卸载时的强制清理，并确保 open 状态变化与挂载/卸载逻辑完全对称。

### P2 - 登录页验证码倒计时 interval 未在卸载时清理

- 定位：`src/views/LoginView.vue:34-50`
- 现象：`startResendCountdown()` 使用 `window.setInterval()`，但组件没有 `onUnmounted()` 清理。
- 影响：路由切换或页面销毁后，定时器仍可能存活，造成不必要的状态更新。
- 建议：增加卸载清理，并在组件销毁时把 `resendTimer` 复位。

### P2 - 通知 SSE 断线后不重连，实时通知会静默失效

- 定位：`src/store/notificationStore.ts:44-60`
- 现象：`EventSource.onerror` 里只做了 `close()` 和清空引用，没有重连策略。
- 影响：网络抖动、代理超时或服务器短暂断开后，实时通知通道不会恢复，功能退化为半静态。
- 建议：增加带退避的重连，或者在 `refreshUnread()` / `fetchList()` 的触发路径上补偿。

### P3 - 主业务 chunk 过大，首屏性能有风险

- 定位：构建产物 `dist/assets/BoardViewContent-*.js`
- 现象：构建输出显示该 chunk 约 `1.17 MB`，gzip 后约 `368 KB`。
- 影响：登录后主路径加载压力较大，尤其在中低端设备或网络较差时更明显。
- 建议：进一步拆分高权重编辑器、图表、甘特图等模块，或按功能路由继续切 chunk。

## Verification

- `npm run test`：通过，`47` 个测试文件、`227` 个测试用例全部通过。
- `npm run build`：通过，但有大 chunk 警告。

## Follow-up Priority

1. 先修复 `taskStore.updateTask()` 的回滚问题。
2. 再加 `TaskEditor` 的异步请求竞态保护。
3. 补齐通知和登录页的资源清理。
4. 之后再做 chunk 拆分优化。
