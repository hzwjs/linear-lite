# Markdown 协作编码一致性修复记录 V1

日期：2026-10-20。

## 根因

导入链路：`MarkdownDocumentImportService.importFile` → `MarkdownToBlockNoteConverter.convertFile` → `DocumentCollaborationService.createInitialState` → Node `encodeDocument` / `materializeDocument`。

`createInitialState` 通过 JSON 树相等检查确认初始正文与 Yjs 回读正文一致。转换器与 ProseMirror 的文本分段规则存在差异：

- CommonMark `SoftLineBreak` 缺少处理分支，源文件中的行界丢失。
- 同样式相邻文本按 AST 节点分段；ProseMirror 合并这些文本。
- 同址相邻链接按 AST 节点分段；ProseMirror 合并链接范围。
- 空代码块和空折叠标题生成空文本节点；ProseMirror 删除空文本节点。

此前 `d201580` 的测试检查块属性集合，缺少 Java 转换器与 Node 产品 schema / Yjs 编码器之间的往返测试。

## 修复

- `appendText` 统一文本生成：保留软／硬换行、合并同样式相邻文本、跳过空文本。
- 行内代码继承外层粗体、斜体等样式。
- 同址相邻链接共用链接范围，链接内部文本遵循样式边界。
- 代码块与折叠标题通过 `appendText` 生成正文。
- 保留 `sameProjection` 严格相等检查；数据路径保持 Markdown → BlockNote → Yjs。

## 原文件证据

文件：`001-易货平台产品设计说明书_APP_PRD_1.1.md`，299,035 字节。

SHA-256：`4490a27062721cf553f70f35a8c7ddb41c6562eaa46dd3b5a10919a034fad002`。

通过真实 Java 转换器与 Node 产品编码器执行两次往返：修复前每次 299 处 JSON 差异；修复后每次 0 处差异。回读的行界检查通过。

顶层块数量为 2,055：标题 407、段落 178、表格 55、无序列表项 332、有序列表项 1,083。

源文件作为本机输入读取，测试与本记录保存通用样例和摘要。

## 回归证据

`MarkdownCollaborationRoundTripTest` 通过 Node 子进程调用产品 `document-codec.ts`，使用 `collaboration-schema.ts` 与 Yjs，比较编码前后的整棵 JSON 树。测试包含 18 个输入，覆盖软／硬换行、转义、行内 HTML、链接合并与地址边界、嵌套样式、空单元格、表格对齐、嵌套列表、折叠区、空标题、代码块、图片、分隔符，以及 MCP 文档和任务描述入口。

最小三行文本样例在修复前触发“协作服务编码回读与初始正文不一致”，修复后通过。

验证命令（服务端目录）：

```sh
rtk mvn -B -Dexec.skip=true -Dtest=MarkdownCollaborationRoundTripTest,MarkdownToBlockNoteConverterTest,MarkdownDocumentImportServiceTest,ProjectDocumentImportControllerTest,ProjectDocumentCommandServiceTest,DocumentCollaborationServiceTest,McpDispatcherTest,McpDocumentPatchServiceTest test
```

结果：8 个测试类、71 项测试通过，失败 0、错误 0、跳过 0；Java 编译通过。

## 交付边界

- 服务器发布待执行。
- 数据库写入、用户文档上传验证待执行。
- E2E 未执行：用户未显式调用。

## 实施事项清单

- [x] 原文件复现
- [x] 定位文本分段差异
- [x] 统一转换器输出
- [x] 增加真实编码回归
- [x] 验证原文件与相关调用链
