# R2 Image Upload UX Design

## Overview

优化任务描述中的图片上传交互：用户粘贴或拖拽图片后，编辑器立即插入本地预览，不等待 R2 上传完成；每张图片都拥有独立上传状态。上传过程中在图片下方显示状态条，上传失败时保留预览并提供重试、删除操作。

本版范围：

- 支持多张图片同时粘贴或拖拽
- 每张图片独立维护 `uploading` / `failed` / `uploaded` 状态
- 图片插入后立即显示本地预览
- 上传中显示图片下方状态条
- 上传失败保留本地预览，并提供 `Retry` / `Remove`
- 上传成功后将本地预览替换为 R2 公网 URL
- 最终保存到数据库的描述中只允许写入已上传成功的图片

不在本版范围：

- 精确百分比进度
- 断点续传
- 自动压缩
- 批量取消上传
- 粘贴远程 HTML 图片时自动转存

## Goals

- 消除粘贴后 3-4 秒“无反应”的体验
- 让用户明确看到每张图片当前的上传状态
- 避免失败时图片直接消失，保留可恢复上下文
- 保证数据库中不会写入 `blob:` 本地 URL 或未完成上传的图片

## Current Problem

当前行为是：

1. 用户粘贴图片
2. 前端等待上传完成
3. 上传成功后再插入正式图片

这导致：

- 网络慢时编辑器没有即时反馈
- 用户无法区分“已粘贴但在上传”和“没有成功粘贴”
- 多图场景下状态不可见
- 上传失败时缺少图内恢复路径

## User Flow

### Success Flow

1. 用户粘贴一张或多张图片
2. 每张图片立即显示本地预览
3. 每张图片下方显示 `Uploading...` 状态条
4. 后台分别上传到 R2
5. 某张图片上传成功后：
   - 预览图 `src` 替换为公网 URL
   - 状态条消失
   - 图片成为可保存内容

### Failure Flow

1. 用户粘贴图片
2. 本地预览立即显示
3. 上传失败后：
   - 预览保留
   - 状态条切换为失败态
   - 显示 `Retry` / `Remove`
4. 用户可：
   - 点击 `Retry` 重新上传该图片
   - 点击 `Remove` 将该图片从编辑器移除

### Multi-image Flow

- 多张图片同时插入后，各自显示自己的状态条
- 任一图片失败不影响其它图片完成
- 重试只影响对应图片

## Approaches Considered

### Option A: 全局上传提示，不改图片节点

优点：

- 实现简单

缺点：

- 不能表达“哪一张图在上传”
- 无法支持逐张失败恢复
- 体验改进有限

### Option B: 图片节点扩展本地状态

优点：

- 体验最好
- 多图和失败恢复都自然成立
- UI 与图片本体绑定，认知成本最低

缺点：

- 需要扩展 Tiptap 图片节点属性和渲染逻辑

### Option C: 编辑器外单独渲染上传队列

优点：

- 实现中等

缺点：

- 图片与状态脱节
- 删除、重试映射复杂

## Recommendation

采用 Option B：扩展图片节点并在节点级维护上传状态。

这是唯一能同时满足“立即预览”“多图独立状态”“失败保留并可重试”的方案。

## Architecture

### Editor Data Model

图片节点需扩展以下属性：

- `src`: 当前展示地址，上传前为 `blob:`，成功后为公网 URL
- `alt`: 图片说明，首版可固定为 `image`
- `uploadState`: `uploading | failed | uploaded`
- `localId`: 前端生成的唯一标识，用于匹配上传任务
- `errorMessage`: 失败原因，用于状态条展示

这些属性仅用于编辑器运行态。最终序列化到 Markdown 时，只保留 `uploaded` 图片的正式 URL。

### Rendering Strategy

建议把当前 Tiptap 图片节点改为自定义 NodeView，而不是继续只依赖默认图片扩展渲染。

NodeView 负责：

- 渲染图片预览
- 在图片下方渲染状态条
- 在失败态渲染 `Retry` / `Remove`

### Upload Lifecycle

插入图片后立即：

1. 生成 `blob:` URL
2. 插入图片节点，状态为 `uploading`
3. 异步调用上传接口

上传完成后：

- 成功：更新同一节点的 `src` 和 `uploadState`
- 失败：更新同一节点的 `uploadState` 和 `errorMessage`

### Save Guard

保存前必须做图片节点过滤：

- `uploaded`：允许进入最终 Markdown
- `uploading`：不允许保存，提示仍有图片上传中
- `failed`：不允许保存，提示存在失败图片，需重试或删除

这样可以确保数据库中不会出现本地预览 URL 或无效图片节点。

## UI Design

### Uploading State

图片下方显示轻量状态条：

- 文案：`Uploading...`
- 左侧可有旋转图标
- 不阻塞继续编辑文本

### Failed State

图片下方状态条切换为失败态：

- 文案：`Upload failed`
- 操作：`Retry`、`Remove`

### Uploaded State

- 状态条消失
- 图片恢复为普通内容

## Validation Rules

沿用已有规则：

- 支持 `png`、`jpg`、`jpeg`、`webp`、`gif`
- 单文件最大 `10MB`

新增交互规则：

- 校验失败时不插入本地预览
- 只有真正开始上传的图片才生成图片节点

## Testing Strategy

### Frontend Tests

- 粘贴多张图片时插入多个本地预览节点
- 每张图片初始状态为 `uploading`
- 上传成功后节点 `src` 替换为公网 URL
- 上传失败后节点保留并进入 `failed`
- 点击 `Retry` 会重新触发对应图片上传
- 点击 `Remove` 会删除对应图片节点
- 保存时若存在 `uploading` 或 `failed` 节点，不能写入最终 Markdown

### Regression Tests

- 已上传图片仍能正确序列化为 Markdown
- 普通文本、列表、代码块等现有编辑能力不受影响

## Risks

- NodeView 引入后，Markdown <-> HTML 转换与编辑器运行态属性可能出现不一致
- `blob:` URL 需要在节点删除或组件销毁时释放，避免内存泄漏
- 多图并发时，要避免状态更新误作用到错误节点

## Rollout Notes

上线前需重点验证：

- 单图慢网体验
- 多图并发上传
- 失败后重试与删除
- 编辑完成后的最终保存结果
