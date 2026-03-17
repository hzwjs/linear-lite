# 运管平台任务计划表导入

## 方式一：转成标准 CSV 后从页面导入（推荐）

1. 在项目根目录执行，将 xlsx 转为系统标准导入 CSV：

```bash
node scripts/convert-yunguan-xlsx-to-import-csv.mjs docs/tmp/运管平台任务计划表_重构版v2.xlsx
```

2. 输出文件：`docs/tmp/运管平台任务计划表_重构版v2_标准导入.csv`（表头与系统模板一致：title, importId, parentImportId, description, status, priority, assignee, dueDate）。

3. 打开看板 → 点击「导入」→ 选择该 CSV 文件 → 确认列映射与预览 → 执行导入。

说明：责任人列会原样写入（如郭震、陈奇）；若与工作区用户名不一致，预览会报错，可在 CSV 中清空 assignee 列或改为对应用户名后重新上传。

---

## 方式二：命令行直接调 API 导入

1. 确保后端与前端已启动（如 `pnpm dev`）。
2. 在浏览器登录，复制 Local Storage 中 `linear_lite_token` 的值。
3. 在项目根目录执行：

```bash
PROJECT_ID=1 JWT=<token> node scripts/import-tasks-from-xlsx.mjs docs/tmp/运管平台任务计划表_重构版v2.xlsx
```

仅解析不请求 API：加 `--dry-run`。
