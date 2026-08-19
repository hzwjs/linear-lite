import { createServer } from 'node:http'

const CONFIG_PAGE = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Pi Bridge · 配置</title>
  <style>
    :root { color-scheme: dark; --bg: #10131a; --panel: #181d27; --panel-2: #202735; --text: #f5f7fb; --muted: #9aa5b5; --line: #303949; --accent: #9fe870; --danger: #ff8f8f; }
    * { box-sizing: border-box; }
    body { margin: 0; min-height: 100dvh; background: radial-gradient(circle at 12% 0%, #23351f 0, transparent 34%), var(--bg); color: var(--text); font: 16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    main { width: min(920px, calc(100% - 32px)); margin: 0 auto; padding: 56px 0 72px; }
    header { margin-bottom: 32px; }
    .eyebrow { color: var(--accent); font-size: 12px; font-weight: 700; letter-spacing: .14em; text-transform: uppercase; }
    h1 { margin: 8px 0; font-size: 40px; letter-spacing: -.04em; line-height: 1.05; }
    header p { max-width: 660px; margin: 0; color: var(--muted); }
    .panel { padding: 24px; border: 1px solid var(--line); border-radius: 18px; background: color-mix(in srgb, var(--panel) 93%, transparent); box-shadow: 0 18px 60px #0004; }
    .panel + .panel { margin-top: 16px; }
    .section-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
    .section-heading h2 { margin: 0; font-size: 20px; letter-spacing: -.02em; }
    .section-heading p { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
    .step { color: var(--accent); font-size: 12px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }
    .status-chip { display: inline-flex; align-items: center; gap: 7px; min-height: 28px; padding: 0 10px; border: 1px solid var(--line); border-radius: 999px; color: var(--muted); font-size: 12px; white-space: nowrap; }
    .status-chip::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
    .status-chip.ready { border-color: #547b43; color: var(--accent); }
    form { display: grid; gap: 12px; align-items: end; }
    .connection-form { grid-template-columns: 1fr auto; margin-top: 22px; }
    .mapping-form { grid-template-columns: minmax(220px, 1fr) 2fr auto; margin-top: 20px; }
    label { display: grid; gap: 6px; color: var(--muted); font-size: 13px; }
    input, select { width: 100%; min-height: 44px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel-2); color: var(--text); padding: 0 12px; font: inherit; outline: none; }
    input:focus, select:focus { border-color: var(--accent); box-shadow: 0 0 0 3px #9fe87022; }
    input:disabled, select:disabled { cursor: not-allowed; opacity: .62; }
    button { min-height: 44px; border: 0; border-radius: 10px; padding: 0 18px; background: var(--accent); color: #172014; cursor: pointer; font: inherit; font-weight: 750; transition: filter 160ms ease, transform 160ms ease, opacity 160ms ease; touch-action: manipulation; }
    button:hover { filter: brightness(1.06); }
    button:active { transform: translateY(1px); }
    button:focus-visible, input:focus-visible, select:focus-visible { outline: 3px solid #9fe87088; outline-offset: 2px; }
    button:disabled { cursor: wait; opacity: .55; }
    button.secondary { background: transparent; border: 1px solid var(--line); color: var(--text); font-weight: 600; }
    button.text { min-height: 36px; padding: 0 10px; background: transparent; color: var(--muted); font-size: 14px; font-weight: 650; }
    button.text:hover { color: var(--text); filter: none; }
    .form-hint { margin: 12px 0 0; color: var(--muted); font-size: 13px; }
    .connection-status { min-height: 24px; margin-top: 14px; color: var(--muted); font-size: 14px; }
    .connection-status.ready { color: var(--accent); }
    .mapping-area { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--line); }
    .mapping-area[aria-disabled="true"] { opacity: .68; }
    .mapping-editor { padding-bottom: 20px; border-bottom: 1px solid var(--line); }
    .mapping-editor[hidden] { display: none; }
    .editor-title { margin: 0; font-size: 14px; font-weight: 700; }
    .editor-hint { margin: 4px 0 0; color: var(--muted); font-size: 13px; }
    .editor-actions { display: flex; align-items: center; gap: 8px; }
    .mapping-list { margin-top: 20px; }
    .list-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; color: var(--muted); font-size: 13px; font-weight: 700; }
    .mapping { display: grid; grid-template-columns: minmax(140px, .65fr) 1fr auto; gap: 16px; align-items: center; padding: 18px 0; border-top: 1px solid var(--line); }
    .mapping:first-child { margin-top: 8px; }
    .key { font-weight: 700; word-break: break-word; }
    .path { color: var(--muted); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; overflow-wrap: anywhere; }
    .status { display: inline-flex; align-items: center; gap: 6px; margin-top: 5px; color: var(--accent); font-size: 12px; }
    .status.invalid { color: var(--danger); }
    .status::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
    #message { min-height: 24px; margin: 12px 0 0; color: var(--accent); font-size: 14px; }
    #message.error { color: var(--danger); }
    .empty { padding: 24px 0 4px; color: var(--muted); text-align: center; }
    @media (max-width: 900px) { .connection-form, .mapping-form { grid-template-columns: 1fr 1fr; } form button { grid-column: 1 / -1; } }
    @media (max-width: 700px) { .section-heading { align-items: stretch; flex-direction: column; } form, .mapping { grid-template-columns: 1fr; } form button { grid-column: auto; } .mapping button { width: fit-content; } main { padding-top: 32px; } h1 { font-size: 32px; } }
    @media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; transition-duration: .01ms !important; } }
  </style>
</head>
<body>
  <main>
    <header>
      <div class="eyebrow">Pi Bridge / Local setup</div>
      <h1>Pi Bridge 配置</h1>
      <p>Linear Lite 地址由当前任务页面在本机执行绑定时自动确定；这里仅维护项目到本地目录的映射。</p>
    </header>
    <section class="panel">
      <div class="section-heading">
        <div><div class="step">Runtime</div><h2>页面绑定状态</h2><p>打开 Linear Lite 任务的本地 Pi 面板后，Bridge 会自动使用该页面对应的后端。</p></div>
        <span id="connection-badge" class="status-chip">等待页面绑定</span>
      </div>
      <div id="connection-status" class="connection-status" role="status" aria-live="polite"></div>
    </section>
    <section class="panel" id="mapping-panel">
      <div class="section-heading">
        <div><div class="step">Step 2</div><h2>绑定项目目录</h2><p>每个 Linear Lite 项目绑定一个本地目录，Pi 会直接在该目录中执行任务。</p></div>
        <button class="secondary" id="new-mapping" type="button">添加项目绑定</button>
      </div>
      <div id="mapping-area" class="mapping-area" aria-disabled="true">
        <div id="mapping-editor" class="mapping-editor" hidden>
          <p id="editor-title" class="editor-title">添加项目绑定</p>
          <p class="editor-hint">只显示当前登录用户可访问的项目。</p>
          <form id="mapping-form" class="mapping-form">
            <label>Linear Lite 项目<select name="projectId" required><option value="">等待页面绑定</option></select></label>
            <label>本地目录绝对路径<input name="directoryPath" required placeholder="例如 /Users/me/code/linear-lite"></label>
            <div class="editor-actions"><button type="submit">保存绑定</button><button id="cancel-mapping" class="text" type="button">取消</button></div>
          </form>
          <div id="message" role="status" aria-live="polite"></div>
        </div>
        <div class="mapping-list">
          <div class="list-heading"><span>当前绑定</span><button class="text" id="refresh" type="button">刷新</button></div>
          <div id="mappings"><div class="empty">等待页面绑定后读取项目列表。</div></div>
        </div>
      </div>
    </section>
  </main>
  <script>
    const form = document.querySelector('#mapping-form')
    const mappingArea = document.querySelector('#mapping-area')
    const mappingEditor = document.querySelector('#mapping-editor')
    const newMappingButton = document.querySelector('#new-mapping')
    const cancelMappingButton = document.querySelector('#cancel-mapping')
    const projectSelect = form.querySelector('[name="projectId"]')
    const directoryInput = form.elements.directoryPath
    const editorTitle = document.querySelector('#editor-title')
    const message = document.querySelector('#message')
    const connectionBadge = document.querySelector('#connection-badge')
    const connectionStatus = document.querySelector('#connection-status')
    const mappings = document.querySelector('#mappings')
    const state = { settingsConfigured: true, availableProjects: [], mappings: [], editingProjectId: null }
    const showMessage = (text, error = false) => { message.textContent = text; message.className = error ? 'error' : ''; message.setAttribute('role', error ? 'alert' : 'status') }
    const showConnectionStatus = (text, ready = false) => { connectionStatus.textContent = text; connectionStatus.className = ready ? 'connection-status ready' : 'connection-status' }
    const healthMessage = (health) => {
      if (health.status === 'online') return ['Linear Lite 已连接。', true]
      if (health.status === 'connecting' || health.status === 'degraded') return ['Bridge 正在重连 Linear Lite…', false]
      if (health.status === 'waiting_for_browser') return ['等待任务详情建立本机执行连接。', false]
      if (health.status === 'stopping') return ['Bridge 正在停止。', false]
      return ['等待任务详情建立本机执行连接。', false]
    }
    const setConnectionState = () => {
      state.settingsConfigured = true
      connectionBadge.textContent = '页面自动绑定'
      connectionBadge.className = 'status-chip ready'
      mappingArea.setAttribute('aria-disabled', 'false')
      newMappingButton.disabled = false
    }
    const node = (tag, text, className) => { const item = document.createElement(tag); item.textContent = text; if (className) item.className = className; return item }
    function resetEditor() {
      state.editingProjectId = null
      mappingEditor.hidden = true
      form.reset()
      editorTitle.textContent = '添加项目绑定'
      projectSelect.disabled = false
      newMappingButton.textContent = '添加项目绑定'
      showMessage('')
    }
    function renderProjectOptions() {
      const current = state.editingProjectId == null ? '' : String(state.editingProjectId)
      const mappedIds = new Set(state.mappings.map((item) => String(item.projectId)))
      const projects = state.availableProjects.filter((project) => String(project.projectId) === current || !mappedIds.has(String(project.projectId)))
      projectSelect.replaceChildren(new Option(projects.length ? '请选择项目' : '没有可绑定的项目', ''))
      for (const project of projects) projectSelect.append(new Option(project.projectName, project.projectId))
      if (current && projects.some((project) => String(project.projectId) === current)) projectSelect.value = current
      projectSelect.disabled = !projects.length || state.editingProjectId != null
    }
    function renderMappings() {
      mappings.replaceChildren()
      if (!state.mappings.length) { mappings.append(node('div', '还没有项目绑定，点击“添加项目绑定”开始。', 'empty')); return }
      for (const item of state.mappings) {
        const row = node('div', '', 'mapping')
        const key = node('div', item.projectName, 'key')
        const details = node('div')
        details.append(node('div', item.directoryPath, 'path'))
        details.append(node('div', item.valid ? '本地目录可用' : item.error, item.valid ? 'status' : 'status invalid'))
        const actions = node('div')
        const edit = document.createElement('button')
        edit.type = 'button'; edit.className = 'text'; edit.textContent = '编辑'
        edit.addEventListener('click', () => {
          state.editingProjectId = item.projectId
          mappingEditor.hidden = false
          editorTitle.textContent = '编辑项目绑定'
          directoryInput.value = item.directoryPath
          newMappingButton.textContent = '取消编辑'
          renderProjectOptions()
          directoryInput.focus()
        })
        const remove = document.createElement('button')
        remove.type = 'button'; remove.className = 'text'; remove.textContent = '移除'
        remove.addEventListener('click', async () => {
          if (!confirm('确定移除项目「' + item.projectName + '」的本地绑定吗？')) return
          const response = await fetch('/api/projects/' + encodeURIComponent(item.projectId), { method: 'DELETE' })
          const body = await response.json()
          if (!response.ok) { showMessage(body.message || '移除绑定失败', true); return }
          if (state.editingProjectId === item.projectId) resetEditor()
          showMessage('绑定已移除'); await loadMappings()
        })
        actions.append(edit, remove); row.append(key, details, actions); mappings.append(row)
      }
    }
    async function loadSettings() {
      const response = await fetch('/api/settings')
      const body = await response.json()
      if (!response.ok) throw new Error(body.message || '读取连接配置失败')
      setConnectionState()
      showConnectionStatus('正在检查页面绑定状态…')
      renderProjectOptions(); renderMappings()
    }
    async function loadBridgeHealth() {
      const response = await fetch('/healthz', { cache: 'no-store' })
      const body = await response.json()
      if (!response.ok) throw new Error(body.message || '读取 Bridge 状态失败')
      const [messageText, ready] = healthMessage(body)
      showConnectionStatus(messageText, ready)
    }
    async function loadAvailableProjects() {
      const response = await fetch('/api/available-projects')
      const body = await response.json()
      if (!response.ok) throw new Error(body.message || '读取 Linear Lite 项目失败')
      state.availableProjects = body.projects
      renderProjectOptions()
    }
    async function loadMappings() {
      const response = await fetch('/api/projects')
      const body = await response.json()
      if (!response.ok) throw new Error(body.message || '读取项目绑定失败')
      state.mappings = body.projects
      renderProjectOptions(); renderMappings()
    }
    async function refreshProjectData() {
      try { await Promise.all([loadAvailableProjects(), loadMappings()]); showMessage('') }
      catch (error) { showMessage(error.message, true); renderMappings() }
    }
    newMappingButton.addEventListener('click', () => {
      if (mappingEditor.hidden) {
        state.editingProjectId = null
        mappingEditor.hidden = false
        editorTitle.textContent = '添加项目绑定'
        newMappingButton.textContent = '取消添加'
        renderProjectOptions()
        projectSelect.focus()
      } else resetEditor()
    })
    cancelMappingButton.addEventListener('click', resetEditor)
    form.addEventListener('submit', async (event) => {
      event.preventDefault()
      const submitButton = form.querySelector('button[type="submit"]')
      submitButton.disabled = true
      showMessage('正在校验本地目录…')
      const data = new FormData(form)
      const projectId = data.get('projectId').trim()
      const project = state.availableProjects.find((item) => String(item.projectId) === projectId)
      const directoryPath = data.get('directoryPath').trim()
      try {
        const response = await fetch('/api/projects/' + encodeURIComponent(projectId), { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ projectName: project?.projectName, directoryPath }) })
        const body = await response.json()
        if (!response.ok) throw new Error(body.message || '保存绑定失败')
        state.editingProjectId = null
        form.reset()
        editorTitle.textContent = '添加项目绑定'
        newMappingButton.textContent = '取消添加'
        projectSelect.disabled = false
        await loadMappings(); showMessage('已保存项目绑定：' + body.projectName)
      } catch (error) { showMessage(error.message, true) }
      finally { submitButton.disabled = false }
    })
    document.querySelector('#refresh').addEventListener('click', async () => {
      try { await loadSettings(); await refreshProjectData(); await loadBridgeHealth() }
      catch (error) { showConnectionStatus(error.message); showMessage(error.message, true) }
    })
    async function initialize() {
      try { await loadSettings(); await refreshProjectData(); await loadBridgeHealth() }
      catch (error) { showConnectionStatus(error.message); showMessage(error.message, true) }
    }
    initialize()
  </script>
</body>
</html>`

function sendJson(response, status, body, request) {
  const origin = request?.headers.origin
  const requestPath = new URL(request?.url ?? '/', 'http://127.0.0.1').pathname
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  }
  // 健康检查和一次性 attach 需要支持当前页面来源；项目目录接口仍只允许本机或显式部署来源。
  const allowedOrigins = new Set([
    'http://124.223.84.101:9080',
    ...(process.env.PI_BRIDGE_ALLOWED_ORIGIN ? [process.env.PI_BRIDGE_ALLOWED_ORIGIN] : []),
  ])
  const publicRuntimeEndpoint = requestPath === '/healthz' || requestPath === '/api/attach'
  if (origin && (publicRuntimeEndpoint || /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin) || allowedOrigins.has(origin))) {
    headers['Access-Control-Allow-Origin'] = origin
    headers['Access-Control-Allow-Methods'] = 'GET,POST,PUT,DELETE,OPTIONS'
    headers['Access-Control-Allow-Headers'] = 'Content-Type'
    headers.Vary = 'Origin'
  }
  response.writeHead(status, headers)
  response.end(JSON.stringify(body))
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let body = ''
    request.on('data', (chunk) => {
      body += chunk.toString()
      if (body.length > 64 * 1024) reject(new Error('请求体过大'))
    })
    request.on('end', () => resolve(body))
    request.on('error', reject)
  })
}

export function createConfigServer({ store, settingsStore, projectProvider, onAttach = null, healthProvider, host = '127.0.0.1', port = 9780 } = {}) {
  if (!store) throw new Error('ProjectConfigStore is required')
  if (!settingsStore) throw new Error('BridgeSettingsStore is required')
  if (!projectProvider) throw new Error('projectProvider is required')
  if (!healthProvider) throw new Error('healthProvider is required')
  const server = createServer(async (request, response) => {
    const url = new URL(request.url ?? '/', `http://${host}`)
    try {
      if (request.method === 'OPTIONS') {
        sendJson(response, 204, null, request)
        return
      }
      if (request.method === 'GET' && url.pathname === '/') {
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
        response.end(CONFIG_PAGE)
        return
      }
      if (request.method === 'GET' && url.pathname === '/healthz') {
        sendJson(response, 200, healthProvider(), request)
        return
      }
      if (request.method === 'GET' && url.pathname === '/api/settings') {
        sendJson(response, 200, await settingsStore.publicSettings())
        return
      }
      if (request.method === 'POST' && url.pathname === '/api/attach') {
        if (!onAttach) throw new Error('本机执行绑定暂不可用')
        let body
        try { body = JSON.parse(await readBody(request) || '{}') } catch { throw new Error('请求体不是有效 JSON') }
        sendJson(response, 200, await onAttach(body), request)
        return
      }
      if (request.method === 'GET' && url.pathname === '/api/projects') {
        sendJson(response, 200, { projects: await store.list() })
        return
      }
      if (request.method === 'GET' && url.pathname === '/api/available-projects') {
        sendJson(response, 200, { projects: await projectProvider() })
        return
      }
      const match = url.pathname.match(/^\/api\/projects\/([^/]+)$/)
      if (match && request.method === 'PUT') {
        const projectId = decodeURIComponent(match[1])
        let body
        try { body = JSON.parse(await readBody(request) || '{}') } catch { throw new Error('请求体不是有效 JSON') }
        const saved = await store.save(projectId, body.projectName, body.directoryPath)
        sendJson(response, 200, saved)
        return
      }
      if (match && request.method === 'DELETE') {
        await store.remove(decodeURIComponent(match[1]))
        sendJson(response, 200, { ok: true })
        return
      }
      sendJson(response, 404, { message: '配置接口不存在' })
    } catch (error) {
      const status = /配置文件|JSON|请求体/.test(error.message) ? 500 : 400
        sendJson(response, status, { message: error.message }, request)
    }
  })
  return {
    server,
    listen() {
      return new Promise((resolvePromise, reject) => {
        server.once('error', reject)
        server.listen(port, host, () => { server.removeListener('error', reject); resolvePromise(server.address()) })
      })
    },
    close() {
      return new Promise((resolvePromise, reject) => server.close((error) => error ? reject(error) : resolvePromise()))
    },
  }
}
