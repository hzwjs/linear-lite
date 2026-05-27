# Linear Lite Logo 实现计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 按任务逐步执行本计划。

**目标：** 生成 Linear Lite 的 SVG logo 资产，并替换默认 Vite favicon。

**方案概述：** 使用纯 SVG 绘制几何 `L` 标记，保持当前产品的浅色、细线、slate 灰蓝视觉基调。文档目录保存评审用完整资产，`public/` 保存应用运行时资产。

**技术栈：** SVG、HTML 预览、Vite 静态资源。

---

### 任务 1：生成 logo 资产

**涉及文件：**

- 新增：`public/logo-mark.svg`
- 新增：`public/logo-lockup.svg`
- 新增：`docs/logo/linear-lite-logo.svg`
- 新增：`docs/logo/linear-lite-logo-dark.svg`
- 新增：`docs/logo/preview.html`

**步骤 1：绘制标记**

创建 64x64 SVG。外层为 14px 圆角容器，内部为连续折线 `L`，末端加 5px 节点。

**步骤 2：绘制 lockup**

在标记右侧添加 `Linear Lite` 字样，使用系统 sans-serif，保持紧凑字距。

**步骤 3：生成预览页**

用 `docs/logo/preview.html` 同时展示浅色、深色、小尺寸、favicon 尺寸。

### 任务 2：接入应用 favicon

**涉及文件：**

- 修改：`index.html`

**步骤 1：替换默认图标路径**

将 `/vite.svg` 替换为 `/logo-mark.svg`。

**步骤 2：验证引用**

运行：`rg -n "vite.svg|logo-mark.svg|logo-lockup.svg" index.html public docs/logo`

预期：`index.html` 只引用 `/logo-mark.svg`，Vite 默认图标不再作为 favicon 使用。

### 任务 3：验证 SVG 可解析

**涉及文件：**

- 验证：`public/logo-mark.svg`
- 验证：`public/logo-lockup.svg`
- 验证：`docs/logo/linear-lite-logo.svg`
- 验证：`docs/logo/linear-lite-logo-dark.svg`

**步骤 1：XML 解析检查**

运行：`node -e "const fs=require('fs');const files=['public/logo-mark.svg','public/logo-lockup.svg','docs/logo/linear-lite-logo.svg','docs/logo/linear-lite-logo-dark.svg'];for (const file of files){const text=fs.readFileSync(file,'utf8');if(!text.includes('<svg')||!text.includes('</svg>')) throw new Error(file);console.log(file,'ok')}"`

预期：四个 SVG 均输出 `ok`。
