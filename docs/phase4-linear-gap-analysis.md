# Phase 4：Linear 与 Linear Lite UI/UX 差距分析

基于对 Linear 官方文档、Changelog 及当前代码的对照，整理「页面与交互模型」「视觉层次」「细节」三类差距，并给出可落地的改进方向。你提到的「Issue 不是抽屉」「页面用线条切分、不是内容浮在背景上」作为主线举一反三。

---

## 关于本分析的方法与局限

**已做的**：用浏览器实际打开了 Linear 的页面。  
- 打开了 **https://linear.app**（首页）、**https://linear.app/login**（登录页）。  
- 首页加载后，可访问性快照中能看到其**嵌入的产品 demo** 的 DOM 结构（顶栏 New issue / Inbox / My issues、侧栏 Favorites、看板列与「Add issue」、Issue 详情区有 Previous/Next issue、Close chat、Assign to 等）。  
- **未进入真实应用**：登录需 Google / 邮箱 / SAML SSO，无法在自动化浏览器内登录，因此**没有看到真实 Workspace 下的列表、看板、Issue 详情布局与视觉**。

**结论**：  
- 下文「一、二、三」中关于 Linear 的表述，**部分来自官方 Changelog/文档**（如 2021 Issue view layout），**部分来自首页嵌入 demo 的 DOM 推断**（如列头 Add issue、Issue 区有 Close/Previous/Next）。  
- **未在真实应用内逐屏验证**：线条分割、卡片是否「浮」、Issue 打开后究竟是侧栏还是居中分栏等，以你在本机**登录 Linear 后实际所见**为准；若与下文不一致，应以你的观察为准并修正本文档。

**建议**：你在本机登录 Linear，打开一个项目的 Issues（List / Board）、点开一条 Issue，对照本文档与当前 Linear Lite 实现，把「和 Linear 不一致」或「文档写错」的地方记下来，再迭代分析文档与改版优先级。

---

## 基于浏览器实际打开的发现（2025-03）

- **首页 (linear.app)**：页面内嵌了产品 demo。从 a11y 快照可见：顶栏有「New issue」「Search workspace」「Inbox」「My issues」等；左侧有「Favorites」及若干项（如 Faster app launch、Agent tasks、UI Refresh）；主区有**三列**，每列有 **「Add issue」** 按钮（e37、e43、e49）；中间有 Issue 详情区，含「Previous issue」「Next issue」「Close chat」、输入框「Message Cursor…」、按钮「Copy issue URL」「Copy issue ID」「Assign to…」、标签 Performance / iOS 等。  
  - **可推断**：列头有「Add issue」（与文档一致）；Issue 打开后有一个可「Close」的面板，且有 Prev/Next，说明是**同一视图内的面板**而非整页跳转。  
- **登录页 (linear.app/login)**：深色主题，居中「Log in to Linear」，按钮「Continue with Google」「Continue with email」「Continue with SAML SSO」「Log in with passkey」。无应用内 UI 可分析。

---

## 一、页面与交互模型

### 1.1 Issue 视图形态：内联/分栏 vs 抽屉

**Linear（2021 Changelog 明确说明）**  
- Issue 详情不是从侧边「滑出覆盖」列表的抽屉。  
- 大屏下：**Issue 内容居中**，右侧 details 面板**随屏幕等比变宽**，与列表/看板是**同一画布上的布局切换**（中心区域从「列表」变为「Issue 主内容 + 右侧属性」）。  
- 小屏或工作流里可能有「全屏 Issue」或紧凑布局，但整体是「同一页面内的分栏」，不是「浮层盖住列表」。

**我们当前**  
- 打开/新建 Issue 时使用 **固定全屏 overlay + 右侧 640px 滑入 Drawer**。  
- 列表/看板被遮罩（虽已极淡 `rgba(0,0,0,0.04)`）压在下面，**上下文被「盖住」**，心理模型是「弹出一个编辑浮层」而非「当前视图切换到 Issue 视图」。

**更正（基于用户反馈）**  
- **误判**：此前将「主区两栏（左 List/Board | 右 Issue Details）」当作 Linear 的设计并实施，属于严重误判；**Linear 并非这样的布局**。  
- **当前实现**：已恢复为 **overlay + 右侧滑出 Drawer**；列头「+」、线分栏、去卡片化、模态框减轻等其余 Phase 4.1 改动保留。  
- **后续**：若需对齐 Linear 真实交互，应以本机登录 Linear 后的实际界面为准，再定改进方向，不再采用「主区常驻两栏」方案。

---

### 1.2 新建 Issue 的入口与反馈

**Linear**  
- 看板：**列头有「+」**，点击即在**该列内**直接创建一条（或先出现极简 inline 行再展开）。  
- 新建/打开后仍在**同一视图内**，不跳出一个独立「卡片/抽屉」把整屏占满。

**我们当前**  
- 仅顶部「New Issue」+ 快捷键 C，一点就打开**全屏 overlay + Drawer**，与列表/看板割裂。

**改进方向**  
- 看板每列列头增加「+」，点击后在该列插入新任务（可先占位行，或直接打开右侧详情但**不盖住左侧**，见 1.1）。  
- 新建完成后：若采用两栏布局，右侧即显示刚建的那条；若仍用 Drawer，可考虑短暂高亮列表中对应行，再关 Drawer，让用户看到「新项出现在列表里」。

---

## 二、视觉层次：线条切分 vs 卡片浮在背景上

**你提到的感受**：「Linear 的页面是一个整体，用线条切分；我们像内容卡片浮在背景上。」

下面按区域拆开说我们哪里在「造卡片」，以及如何往「线条切分、同一平面」靠拢。

### 2.1 主内容区背景与分割

**Linear**  
- 主内容区与侧栏是**同一浅色体系**，用 **1px 细线（或极淡 border）** 做功能区隔（如侧栏与主区、表头与列表行）。  
- 列表行之间多用**线或留白**分隔，行本身不必带圆角、阴影、独立背景块，看起来是「一张表/一张画布上的行」。

**我们当前**  
- `BoardView` 主区 `padding: 24px 32px`，内部 `.board-columns` 每列是 **border + border-radius + 独立背景** 的「卡片」：  
  `.column { background: var(--color-bg-main); border: 1px solid var(--color-border); border-radius: var(--border-radius-lg); }`  
- 列与列之间 `gap: 24px`，列本身又白底+边框，**视觉上像多张卡片摆在灰底上**，而不是「一块整板用线分栏」。

**改进方向**  
- **看板**：主内容区改为「整块画布」——背景统一（如与侧栏同或仅差一档灰），列与列之间用 **竖线（border-left 或 1px div）** 分割，去掉每列的圆角、或仅保留极弱圆角（2–4px），列头与卡片区用 **横线** 分割。  
- **列表**：我们 List 行已有 `border-bottom: 1px solid var(--color-border)`，方向对；可再检查分组块（`.group`）是否过于「一块块」（如背景+圆角过重），尽量用线/留白区分，少用整块底色卡片。

### 2.2 列表行与看板卡片

**Linear**  
- 列表行：**单行高度紧凑**，行与行之间**细线或留白**，行内信息用对齐与字重区分，不依赖「每行一个圆角白底块」。  
- 看板卡片：卡片可带极轻阴影或线框，但**不抢戏**，整体仍像「板上的格子」，不是「一堆独立小卡片浮在空白里」。

**我们当前**  
- **TaskCard**：`border: 1px` + `border-radius: var(--border-radius-md)` + `padding: 12px` + `margin-bottom: 8px`，且 `background: var(--color-bg-elevated)`，**每张卡都是一块明显的小白块**。  
- **ListView**：`.group` 带 `background: var(--color-hover)` + `border-radius: var(--border-radius-md)`，**每个分组又是一块圆角块**；行内 `.pill` 再一层 `background: var(--color-bg-main)` + `border-radius: 12px`，层级多、块状多。

**改进方向**  
- **看板卡片**：减小或去掉圆角（如 4px 或 2px），边框用**细线**（1px solid），阴影若有则极轻；或尝试「无阴影 + 仅底部分割线」的扁平样式，让卡片像「板上的条」而不是「浮起来的块」。  
- **列表**：分组头用**线+字**区分即可，不必整块背景+圆角；行内 Pills 可保留但降低对比（如与行背景同色或仅字色区分），减少「一堆小卡片」感。

### 2.3 侧栏

**我们当前**  
- 侧栏与主区已有 `border-right: 1px`，方向对。  
- 可再统一：整站**分割一律用 1px 线**，避免某处用线、某处用「留白+卡片」混搭。

### 2.4 弹窗与 Command Palette

**我们当前**  
- CreateProjectModal / ProjectSettingsModal：**居中浮层** + `border-radius: var(--border-radius-lg)` + `box-shadow: 0 8px 32px rgba(0,0,0,0.3)`，**典型「卡片浮在背景上」**。  
- Command Palette：已有 `--shadow-popover`，相对克制。

**改进方向（可选）**  
- 模态框可略减轻阴影、缩小圆角，或改为「从上方/侧边滑入的一整块面板」与主布局衔接，减少「正中间一块卡片」的孤立感。优先级可低于「Issue 视图与看板列表的整合」。

---

## 三、其他可举一反三的细节

### 3.1 表头/列头与主内容一体

**Linear**  
- 列表表头与第一行、看板列头与第一张卡之间，通常用**线**分隔，表头与内容**同一背景**或仅差一档，不单独给表头一块「浮起来的条」。

**我们当前**  
- Board 的 `.column-header` 与 `.column-list` 之间没有明显横线；列头是「列卡片」内的一块区域。若按 2.1 改成「线分栏」，列头与列内容应用**同一背景 + 底部分割线**，避免列头再成一块浮条。

### 3.2 新建/编辑表单的「标题」区域

**Linear**  
- Issue 标题区是**无框、大字号、与描述同一流式布局**，像文档而不是「表单里的一格」。

**我们当前**  
- TaskEditor 已用无边框 textarea + 18–20px 字重，方向对；若改为两栏内联布局（1.1），左侧整块就是「标题 + 描述」的文档流，可继续强化「无框、无卡片」感。

### 3.3 空状态与错误状态

**我们当前**  
- 空状态/错误状态是居中一块文案+按钮，区域感明显。  
- 可改为：与列表/看板**同一网格或同一流式布局**，用线或留白与内容区隔，而不是「中间一个隐式块」。

### 3.4 间距与密度

**Linear**  
- 整体偏紧凑，信息密度高；留白用在「区分区块」而不是「让每个块都浮起来」。

**我们当前**  
- `.board-content { padding: 24px 32px }`，列 `gap: 24px`，单卡 `padding: 12px`、`margin-bottom: 8px`。若改为线分栏，可适当收紧 padding/gap，让「线」承担分隔职责，减少「块与块之间的巨大留白」。

---

## 四、优先级与实施建议

| 优先级 | 方向 | 说明 |
|--------|------|------|
| ~~P0~~ | ~~Issue 视图改为主区两栏~~（已更正：Linear 并非此设计，该方案已撤销） | — |
| **P0** | 看板列用「线分栏」替代「多块圆角卡片列」；主区背景统一为「一块画布」 | 解决「页面用线条切分、不是内容浮在背景上」 |
| **P1** | 看板卡片与列表分组：减圆角、减阴影、用线/留白区分 | 弱化「卡片浮在背景上」 |
| **P1** | 列头「+」新建、新建后反馈与列表高亮 | 与 Linear 新建入口与反馈一致 |
| **P2** | 模态框与空状态：减轻浮层感、与布局衔接 | 全站统一「线+留白」语言 |

建议先做 **P0 两项**（Issue 视图形态 + 看板/主区线条化），再按 P1/P2 迭代；这样「整体像 Linear」的改进会最明显。

---

## 五、与 phase4-ideas / phase4-task-list 的关系

- 本文档是**差距分析与方向**，不替代 Phase 4 已有任务列表。  
- 可将上述 P0/P1 拆成 **Phase 4.1 或 Phase 5** 的 task-list 条目（例如「主区两栏布局」「看板线分栏与去卡片化」「列头 + 新建」等），与现有 P4-8.x 验收并列或后续验收。
