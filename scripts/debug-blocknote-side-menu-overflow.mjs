/**
 * 精准定位 BlockNote 侧栏（bn-side-menu）被裁切：打印从侧栏 / 编辑器根到 <html> 的 computed overflow 链。
 *
 * 前置：本机已 `pnpm dev`。
 *
 * 用法：
 *   pnpm exec playwright install chromium   # 首次
 *   BASE_URL=http://localhost:5173 TASK_PATH=/tasks/LINEAR-LITE-27 pnpm debug:blocknote-overflow
 *
 * 未登录会被重定向到 /login，此时二选一：
 *   A) STORAGE_STATE=/绝对路径/auth.json
 *   B) 仅本次在 shell 里传入（勿写入仓库、勿提交 .env）：
 *      PLAYWRIGHT_LOGIN_IDENTITY=邮箱 PLAYWRIGHT_LOGIN_PASSWORD=密码 pnpm debug:blocknote-overflow
 *
 * 无后端 / 无凭据时自测布局与脚本逻辑（不访问 BASE_URL）：
 *   OVERFLOW_DEBUG_FIXTURE=1 pnpm debug:blocknote-overflow
 */
import { chromium } from 'playwright'

const USE_FIXTURE = process.env.OVERFLOW_DEBUG_FIXTURE === '1'

const BASE_URL = (process.env.BASE_URL || 'http://localhost:5173').replace(/\/$/, '')
const TASK_PATH = process.env.TASK_PATH || '/tasks/LINEAR-LITE-27'
const STORAGE_STATE = process.env.STORAGE_STATE || ''
const LOGIN_IDENTITY = process.env.PLAYWRIGHT_LOGIN_IDENTITY || ''
const LOGIN_PASSWORD = process.env.PLAYWRIGHT_LOGIN_PASSWORD || ''
if (!LOGIN_IDENTITY && process.env.LAYWRIGHT_LOGIN_IDENTITY) {
  console.error('提示：环境变量写成了 LAYWRIGHT_LOGIN_IDENTITY，应为 PLAYWRIGHT_LOGIN_IDENTITY（PLAY 开头）。')
}
if (!LOGIN_PASSWORD && process.env.LAYWRIGHT_LOGIN_PASSWORD) {
  console.error('提示：环境变量写成了 LAYWRIGHT_LOGIN_PASSWORD，应为 PLAYWRIGHT_LOGIN_PASSWORD。')
}
const HOVER_MS = Number(process.env.HOVER_MS || '800')

function taskUrl() {
  return `${BASE_URL}${TASK_PATH.startsWith('/') ? TASK_PATH : `/${TASK_PATH}`}`
}

async function tryPasswordLogin(page) {
  if (!LOGIN_IDENTITY || !LOGIN_PASSWORD) return false

  const form = page.locator('form.login-form')
  if ((await form.count()) === 0) return false

  console.error('→ 检测到登录页，使用 PLAYWRIGHT_LOGIN_* 环境变量执行登录…')
  await form.locator('input[type="text"].login-input').first().fill(LOGIN_IDENTITY)
  await form.locator('input[type="password"]').fill(LOGIN_PASSWORD)
  await Promise.all([
    page.waitForURL((u) => !u.pathname.endsWith('/login'), { timeout: 120_000 }),
    form.locator('button.login-submit').click(),
  ])
  console.error('→ 登录成功，回到任务页')
  return true
}

/** 与当前任务页 DOM/CSS 意图对齐的静态页，用于无登录时回归 overflow 链 */
function overflowFixtureHtml() {
  const flexCol = () =>
    [
      'display:flex',
      'flex-direction:column',
      'flex:1',
      'min-height:0',
      'min-width:0',
      'overflow:visible',
    ].join(';')
  return `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"/><title>overflow fixture</title></head>
<body style="margin:0">
<div id="app" class="app-layout app-layout--task-workspace" style="display:flex;height:720px;width:1280px;overflow:visible">
<main class="main main--task-workspace" style="${flexCol()};overflow:visible">
<div class="board-view board-view--inline-editor" style="${flexCol()};overflow:visible">
<main class="board-content board-content--inline-editor board-content--list" style="${flexCol()};overflow:visible;position:relative">
<div class="workspace-inline-editor" style="${flexCol()};overflow:visible">
<aside class="editor-panel editor-panel--inline" style="${flexCol()};overflow:visible;width:1040px">
<div class="editor-body" style="display:flex;flex:1;min-height:0;overflow:visible;width:1039px">
<div class="editor-content" style="flex:1;min-width:0;min-height:0;padding:16px 20px;display:flex;flex-direction:column;gap:0;overflow:visible">
<div class="editor-content-scroll" style="flex:1;min-width:0;min-height:0;display:flex;flex-direction:column;gap:0;overflow-y:auto;overflow-x:visible">
<section class="content-section description-section" style="position:relative;margin-top:10px;min-height:0;flex-shrink:0;padding-inline-start:56px;overflow:visible">
<div class="blocknote-editor-wrap blocknote-editor-wrap--chrome" style="position:relative;overflow:visible">
<div class="bn-root bn-container light bn-mantine" style="overflow:visible">
<div class="bn-block-outer" style="min-height:28px;padding:2px 0">
<div class="bn-editor tiptap ProseMirror bn-default-styles" style="position:relative;min-height:24px;border:1px dashed #ccc">fixture</div>
</div></div></div></section>
</div></div></div></aside></div></main></div></main></div>
<div id="_r_1_" style="position:static;overflow:visible;width:1280px;height:0"></div>
<div style="position:absolute;left:269px;top:157px;overflow:visible">
<div class="bn-side-menu m_4081bf90 mantine-Group-root" style="width:48px;height:30px;background:#eee;border:1px solid #888">+</div>
</div>
</body></html>`
}

async function ensureTaskEditorVisible(page) {
  const url = taskUrl()
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 120_000 })

  const onLogin =
    page.url().includes('/login') || (await page.locator('form.login-form').count()) > 0

  if (onLogin) {
    if (LOGIN_IDENTITY && LOGIN_PASSWORD) {
      await tryPasswordLogin(page)
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 120_000 })
    } else if (STORAGE_STATE) {
      console.error(
        '已设置 STORAGE_STATE 但仍落在 /login（会话过期或无效）。请更新 auth.json，或临时用 PLAYWRIGHT_LOGIN_IDENTITY + PLAYWRIGHT_LOGIN_PASSWORD。'
      )
      throw new Error('login_required')
    } else {
      console.error(
        '当前需要登录。请任选其一：\n' +
          '  1) PLAYWRIGHT_LOGIN_IDENTITY + PLAYWRIGHT_LOGIN_PASSWORD（仅本机 shell 传入，勿写入仓库）\n' +
          '  2) STORAGE_STATE=.../auth.json（Playwright storageState）\n'
      )
      throw new Error('login_required')
    }
  }

  await page.waitForSelector('.bn-editor', { state: 'visible', timeout: 120_000 })
}

async function collectOverflowReport(page) {
  return page.evaluate(() => {
    function describeNode(n) {
      if (!n) return null
      const cs = getComputedStyle(n)
      const rect = n.getBoundingClientRect()
      return {
        tag: n.tagName.toLowerCase(),
        id: n.id || undefined,
        class:
          typeof n.className === 'string' && n.className
            ? n.className.split(/\s+/).filter(Boolean).slice(0, 8).join(' ')
            : undefined,
        overflow: cs.overflow,
        overflowX: cs.overflowX,
        overflowY: cs.overflowY,
        position: cs.position,
        rect: {
          x: Math.round(rect.x),
          y: Math.round(rect.y),
          w: Math.round(rect.width),
          h: Math.round(rect.height),
        },
      }
    }

    function chainFrom(el) {
      const out = []
      let n = el
      let depth = 0
      while (n && depth < 80) {
        const d = describeNode(n)
        const clips =
          d.overflow === 'hidden' ||
          d.overflow === 'clip' ||
          d.overflow === 'scroll' ||
          d.overflow === 'auto' ||
          d.overflowX === 'hidden' ||
          d.overflowX === 'clip' ||
          d.overflowX === 'auto' ||
          d.overflowX === 'scroll' ||
          d.overflowY === 'hidden' ||
          d.overflowY === 'clip' ||
          d.overflowY === 'auto' ||
          d.overflowY === 'scroll'
        out.push({ depth, clips, ...d })
        n = n.parentElement
        depth++
      }
      return out
    }

    function firstClipper(chain) {
      const hit = chain.find((c) => c.clips)
      return hit
        ? {
            depth: hit.depth,
            tag: hit.tag,
            id: hit.id,
            class: hit.class,
            overflow: hit.overflow,
            overflowX: hit.overflowX,
            overflowY: hit.overflowY,
          }
        : null
    }

    const menu = document.querySelector('.bn-side-menu')
    const editor =
      document.querySelector('.content-section.description-section .bn-editor') ||
      document.querySelector('.blocknote-editor-wrap .bn-editor') ||
      document.querySelector('.bn-editor')

    const chainMenu = menu ? chainFrom(menu) : []
    const chainEd = editor ? chainFrom(editor) : []

    return {
      hasSideMenu: !!menu,
      chainFromSideMenu: chainMenu,
      chainFromEditor: chainEd,
      firstClipperFromSideMenu: menu ? firstClipper(chainMenu) : null,
      firstClipperFromEditor: editor ? firstClipper(chainEd) : null,
    }
  })
}

async function main() {
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext(
    STORAGE_STATE ? { storageState: STORAGE_STATE } : {}
  )
  const page = await context.newPage()

  if (USE_FIXTURE) {
    console.error('→ OVERFLOW_DEBUG_FIXTURE=1：内存静态页（不访问 Vite、不登录）')
    await page.setContent(overflowFixtureHtml(), { waitUntil: 'domcontentloaded', timeout: 30_000 })
    await page.locator('.bn-block-outer').first().hover({ timeout: 5_000 })
    await page.waitForTimeout(HOVER_MS)
    const report = await collectOverflowReport(page)
    console.log(JSON.stringify(report, null, 2))
    const ed = report.firstClipperFromEditor
    const okSide = report.firstClipperFromSideMenu == null
    const okEd = ed && ed.class && ed.class.includes('editor-content-scroll')
    if (!okSide || !okEd) {
      console.error(
        `夹具断言失败：期望 firstClipperFromSideMenu=null 且 firstClipperFromEditor.class 含 editor-content-scroll；实际 side=${JSON.stringify(report.firstClipperFromSideMenu)} ed=${JSON.stringify(ed)}`
      )
      await browser.close()
      process.exit(1)
    }
    console.error('→ 夹具断言通过（侧栏链无裁切祖先；编辑器链首个 clips 为 .editor-content-scroll）')
    await browser.close()
    return
  }

  console.error(`→ ${taskUrl()}`)
  try {
    await ensureTaskEditorVisible(page)
  } catch (e) {
    if (e?.message === 'login_required') process.exit(1)
    console.error('未找到 .bn-editor（任务页加载失败或仍无权限）。')
    throw e
  }

  const descBlock = page.locator('.content-section.description-section .bn-block-outer').first()
  const anyBlock = page.locator('.bn-block-outer').first()
  if ((await descBlock.count()) > 0) {
    await descBlock.hover({ timeout: 10_000 })
  } else {
    await anyBlock.hover({ timeout: 10_000 })
  }
  await page.waitForTimeout(HOVER_MS)

  const report = await collectOverflowReport(page)

  console.log(JSON.stringify(report, null, 2))
  if (!report.hasSideMenu) {
    console.error(
      '\n注意：未检测到 .bn-side-menu（未悬停到块、未开 block chrome、或侧栏被裁到不可见）。可参考 chainFromEditor / firstClipperFromEditor。'
    )
  } else if (report.firstClipperFromSideMenu) {
    const f = report.firstClipperFromSideMenu
    console.error(
      `\n从 .bn-side-menu 向上，第一个可能参与裁切的祖先：depth=${f.depth} <${f.tag}${f.id ? `#${f.id}` : ''}> class="${f.class || ''}" overflow=${f.overflow} ox=${f.overflowX} oy=${f.overflowY}`
    )
  }

  await browser.close()
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
