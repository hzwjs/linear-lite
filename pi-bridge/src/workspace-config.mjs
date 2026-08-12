import { mkdir, readFile, realpath, rename, stat, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { isAbsolute, dirname, resolve } from 'node:path'
import { spawn } from 'node:child_process'

const PROJECT_ID_PATTERN = /^[1-9]\d*$/

function runCommand(command, args) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk) => { stdout += chunk.toString() })
    child.stderr.on('data', (chunk) => { stderr += chunk.toString() })
    child.on('error', reject)
    child.on('close', (code) => {
      if (code === 0) resolvePromise(stdout.trim())
      else reject(new Error(stderr.trim() || `${command} exited ${code}`))
    })
  })
}

export function validateProjectId(projectId) {
  if ((typeof projectId !== 'number' && typeof projectId !== 'string') || !String(projectId).trim()) {
    throw new Error('projectId 不能为空')
  }
  const normalized = String(projectId).trim()
  if (!PROJECT_ID_PATTERN.test(normalized)) {
    throw new Error('projectId 必须是正整数')
  }
  const value = Number(normalized)
  if (!Number.isSafeInteger(value)) {
    throw new Error('projectId 必须是安全范围内的正整数')
  }
  return value
}

function validateProjectName(projectName) {
  if (typeof projectName !== 'string' || !projectName.trim()) {
    throw new Error('projectName 不能为空')
  }
  return projectName.trim()
}

/** 配置保存前统一验证目录和 Git 根目录，任务执行时也会再次验证当前状态。 */
export async function validateRepositoryPath(repositoryPath) {
  if (typeof repositoryPath !== 'string' || !repositoryPath.trim()) {
    throw new Error('repositoryPath 不能为空')
  }
  if (!isAbsolute(repositoryPath)) {
    throw new Error('repositoryPath 必须是绝对路径')
  }

  const candidate = resolve(repositoryPath.trim())
  let info
  try {
    info = await stat(candidate)
  } catch {
    throw new Error(`本地目录不存在：${candidate}`)
  }
  if (!info.isDirectory()) throw new Error(`本地路径不是目录：${candidate}`)

  let canonicalPath
  try {
    canonicalPath = await realpath(candidate)
    const gitRoot = await runCommand('git', ['-C', canonicalPath, 'rev-parse', '--show-toplevel'])
    return (await realpath(gitRoot))
  } catch {
    throw new Error(`本地目录不是有效 Git 仓库：${candidate}`)
  }
}

function emptyConfig() {
  return { projects: [] }
}

function parseConfig(text, filePath) {
  let parsed
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error(`Bridge 配置文件不是有效 JSON：${filePath}`)
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed) || !Array.isArray(parsed.projects)) {
    throw new Error(`Bridge 配置文件格式无效：${filePath}`)
  }

  const projects = []
  const projectIds = new Set()
  for (const project of parsed.projects) {
    if (!project || typeof project !== 'object' || Array.isArray(project)) {
      throw new Error(`Bridge 配置文件中的项目映射无效：${filePath}`)
    }
    // projectName 只供配置页展示；仓库解析和任务定位始终只使用 projectId。
    const projectId = validateProjectId(project.projectId)
    if (projectIds.has(projectId)) {
      throw new Error(`Bridge 配置文件中的 projectId 重复：${projectId}`)
    }
    projectIds.add(projectId)
    const projectName = validateProjectName(project.projectName)
    if (typeof project.repositoryPath !== 'string' || !project.repositoryPath.trim()) {
      throw new Error(`projectId ${projectId} 的 repositoryPath 无效`)
    }
    projects.push({ projectId, projectName, repositoryPath: project.repositoryPath.trim() })
  }
  return { projects }
}

export class ProjectConfigStore {
  constructor(filePath) {
    this.filePath = resolve(filePath)
    this.writeQueue = Promise.resolve()
  }

  async read() {
    try {
      const text = await readFile(this.filePath, 'utf8')
      return parseConfig(text, this.filePath)
    } catch (error) {
      if (error?.code === 'ENOENT') return emptyConfig()
      throw error
    }
  }

  async list() {
    const config = await this.read()
    return Promise.all(config.projects.map(async ({ projectId, projectName, repositoryPath }) => {
      try {
        await validateRepositoryPath(repositoryPath)
        return { projectId, projectName, repositoryPath, valid: true }
      } catch (error) {
        return { projectId, projectName, repositoryPath, valid: false, error: error.message }
      }
    }))
  }

  async resolveRepository(projectId) {
    const normalizedProjectId = validateProjectId(projectId)
    const config = await this.read()
    // 不允许按项目名称或路径猜测映射，未命中 projectId 必须明确失败。
    const project = config.projects.find((item) => item.projectId === normalizedProjectId)
    if (!project) {
      throw new Error(`projectId 未配置本地仓库映射：${normalizedProjectId}`)
    }
    return validateRepositoryPath(project.repositoryPath)
  }

  async save(projectId, projectName, repositoryPath) {
    const normalizedProjectId = validateProjectId(projectId)
    const normalizedProjectName = validateProjectName(projectName)
    const validatedPath = await validateRepositoryPath(repositoryPath)
    return this.enqueueWrite(async () => {
      const config = await this.read()
      const existing = config.projects.find((item) => item.projectId === normalizedProjectId)
      const saved = { projectId: normalizedProjectId, projectName: normalizedProjectName, repositoryPath: validatedPath }
      if (existing) Object.assign(existing, saved)
      else config.projects.push(saved)
      await this.write(config)
      return saved
    })
  }

  async remove(projectId) {
    const normalizedProjectId = validateProjectId(projectId)
    return this.enqueueWrite(async () => {
      const config = await this.read()
      config.projects = config.projects.filter((item) => item.projectId !== normalizedProjectId)
      await this.write(config)
    })
  }

  enqueueWrite(operation) {
    const result = this.writeQueue.then(operation)
    this.writeQueue = result.catch(() => {})
    return result
  }

  async write(config) {
    await mkdir(dirname(this.filePath), { recursive: true })
    const tempPath = `${this.filePath}.${randomUUID()}.tmp`
    await writeFile(tempPath, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 })
    await rename(tempPath, this.filePath)
  }
}
