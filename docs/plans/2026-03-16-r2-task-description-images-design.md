# R2 Task Description Images Design

## Overview

为 Linear Lite 增加任务描述图片能力：用户在任务描述编辑器中粘贴或拖拽图片时，前端自动上传图片到 Cloudflare R2，并将返回的公开对象 URL 以 Markdown 图片语法插入描述内容。

本版范围：

- 存储服务使用 Cloudflare R2
- 文件访问方式使用公开对象 URL
- 交互方式只支持粘贴图片和拖拽图片到描述编辑器
- 支持图片格式：`png`、`jpg`、`jpeg`、`webp`、`gif`
- 单文件大小限制：`10MB`
- 描述字段继续存 Markdown 文本

不在本版范围：

- 预签名直传
- 上传按钮
- 上传进度条
- 自动压缩
- 失败重试
- 删除任务图片时自动回收 R2 对象
- 将外部图片 URL 转存到 R2

## Goals

- 让用户能像常见协作工具一样，直接在任务描述里插入图片
- 保持当前任务描述 Markdown 存储模型不变
- 将存储凭证保留在后端，避免前端暴露 R2 凭证
- 在首版复杂度可控的前提下完成端到端上传链路

## Current Context

当前任务描述编辑链路：

- 描述编辑器组件位于 `src/components/TiptapEditor.vue`
- 任务详情与创建场景都复用该编辑器，例如 `src/components/TaskEditor.vue`、`src/components/IssueComposer.vue`
- 描述内容通过 `src/utils/editorMarkdown.ts` 在 Markdown 与编辑器 HTML 间转换
- 后端任务描述存储在 `tasks.description`，类型为 `TEXT`

当前系统没有：

- 文件上传接口
- 对象存储抽象
- 编辑器图片节点或图片上传逻辑

## User Flow

### Paste Flow

1. 用户在任务描述编辑器中粘贴图片
2. 前端识别剪贴板中的图片文件
3. 前端先做类型和大小校验
4. 前端调用上传接口
5. 后端上传到 R2 并返回公开 URL
6. 前端在当前光标位置插入 Markdown：`![image](https://...)`

### Drag-and-drop Flow

1. 用户将图片拖入任务描述编辑器
2. 前端识别拖入文件中的图片
3. 前端先做类型和大小校验
4. 前端调用上传接口
5. 后端上传到 R2 并返回公开 URL
6. 前端在编辑器当前位置插入 Markdown 图片语法

### Failure Flow

- 非支持图片格式：前端直接提示，不发请求
- 超过大小限制：前端直接提示，不发请求
- 后端上传失败：提示失败，不插入 Markdown
- 非图片粘贴或拖拽：保持现有编辑器行为，不做拦截

## Approaches Considered

### Option A: 后端代理上传到 R2

前端将图片文件上传到应用服务，后端完成 R2 写入并返回公开 URL。

优点：

- 凭证只保留在后端
- 校验、命名规则和错误处理集中
- 与当前 Spring Boot API 风格一致
- 首版改造量最低

缺点：

- 文件流经过应用服务，多一跳
- 后端带宽和内存压力高于直传方案

### Option B: 后端签发预签名 URL，前端直传 R2

优点：

- 后端流量更小
- 上传链路更接近最终形态

缺点：

- 前后端状态处理更复杂
- 需要额外签名与跨域配置
- 对首版来说收益不如复杂度增长明显

### Option C: 将图片内容直接写入 Markdown

优点：

- 不需要对象存储和上传接口

缺点：

- 描述字段会急剧膨胀
- 渲染、传输、存储都很差
- 明显偏离引入 R2 的目标

## Recommendation

选择 Option A：后端代理上传到 R2。

这是当前项目里最稳妥的首版方案。它能复用现有鉴权和 API 基础设施，同时保持描述字段结构不变。后续如果图片量增大，再演进为预签名直传也不会推翻本版数据模型。

## Architecture

### Frontend Responsibilities

前端负责：

- 监听 `paste` 和 `drop` 事件
- 提取图片文件
- 在请求前完成类型和大小校验
- 调用上传接口
- 在上传成功后向编辑器插入 Markdown 图片语法
- 在失败时展示轻量错误提示

建议修改或新增：

- `src/components/TiptapEditor.vue`
- `src/services/api/upload.ts`
- `src/services/api/types.ts`
- 对应 Vitest 测试文件

### Backend Responsibilities

后端负责：

- 提供上传接口
- 校验登录态
- 校验文件类型和大小
- 生成对象 key
- 上传对象到 R2
- 返回公开访问 URL

建议新增或修改：

- `linear-lite-server/src/main/java/com/linearlite/server/controller/UploadController.java`
- `linear-lite-server/src/main/java/com/linearlite/server/service/ObjectStorageService.java`
- `linear-lite-server/src/main/java/com/linearlite/server/service/R2ObjectStorageService.java`
- `linear-lite-server/src/main/java/com/linearlite/server/config/R2StorageProperties.java`
- `linear-lite-server/src/main/resources/application.yml`
- 后端测试文件

## API Design

建议接口：

`POST /api/uploads/images`

请求：

- `Content-Type: multipart/form-data`
- 字段：`file`

成功响应示意：

```json
{
  "success": true,
  "data": {
    "url": "https://pub.example.com/task-images/2026/03/uuid-demo.png",
    "key": "task-images/2026/03/uuid-demo.png"
  }
}
```

失败响应示意：

```json
{
  "success": false,
  "message": "仅支持 png/jpg/jpeg/webp/gif 图片"
}
```

## Storage Design

### Object Key Strategy

对象 key 建议格式：

`task-images/YYYY/MM/<uuid>-<safe-filename>`

例如：

`task-images/2026/03/550e8400-e29b-41d4-a716-446655440000-demo.png`

这样做的原因：

- 避免文件名冲突
- 保留原始扩展名，便于内容类型判断
- 带年月目录，后续排查和生命周期管理更容易

### Public URL Strategy

后端不依赖 R2 SDK 返回的原始对象链接，而是使用配置的 `publicBaseUrl` 拼接最终访问地址：

`<publicBaseUrl>/<key>`

这让本地、测试和生产环境都能按自己的公开域名访问对象。

## Validation Rules

### Frontend Validation

- 仅接受 `image/png`、`image/jpeg`、`image/webp`、`image/gif`
- 单文件大小不得超过 `10MB`
- 多文件粘贴或拖拽时，按顺序逐个处理

### Backend Validation

- 请求中必须包含文件
- MIME 类型必须在允许列表中
- 文件大小不得超过 `10MB`
- 文件名为空时使用默认安全文件名
- 上传失败时返回明确错误，避免前端误插入 Markdown

前后端都做校验。前端负责快速反馈，后端负责安全兜底。

## Editor Behavior

- 成功上传后，将 Markdown 图片语法插入当前选择位置
- 非图片内容不改变当前粘贴逻辑
- 首版不引入专门的图片 Node，继续使用现有 Markdown <-> HTML 转换链路
- 需要确保 `editorMarkdown` 转换链路能正确保留 Markdown 图片语法及渲染结果

如果现有转换链路对图片支持不足，应优先补齐 Markdown 图片的解析与序列化能力，而不是在首版引入新的富文本存储格式。

## Error Handling

- 前端校验失败：展示短消息，终止上传
- 上传接口失败：展示短消息，保留当前编辑内容，不插入图片 Markdown
- 后端配置缺失：返回服务端错误，并记录日志
- R2 写入异常：返回上传失败消息，并记录带 key 的错误日志

## Security Notes

- R2 `accessKeyId` 与 `secretAccessKey` 只放在服务端配置
- 上传接口保持登录态保护
- 文件类型和大小以后端校验为准
- 对文件名进行安全清洗，避免特殊字符污染对象 key

## Testing Strategy

### Frontend Tests

- 编辑器粘贴图片时会调用上传 API
- 编辑器拖拽图片时会调用上传 API
- 上传成功后会插入正确 Markdown
- 非图片粘贴不会触发上传
- 超大图片会被前端阻止

### Backend Tests

- 上传成功时返回 URL 和 key
- 非法 MIME 类型被拒绝
- 超过大小限制被拒绝
- R2 服务抛错时返回失败
- 对象 key 格式符合约定

## Rollout Notes

上线前需要准备：

- Cloudflare R2 bucket
- 公开访问域名或公共对象基地址
- 服务端环境变量
- 如有需要，配置 Spring multipart 大小限制

首版上线后应重点观察：

- 上传失败率
- 大文件错误是否清晰
- Markdown 图片在任务详情和创建流程中的渲染一致性
