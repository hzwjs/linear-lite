import { createHash, randomUUID } from 'node:crypto'
import { execFile } from 'node:child_process'
import { access, mkdir, readFile, rename, unlink, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { promisify } from 'node:util'

const exec = promisify(execFile)
const DRIVER_TIMEOUT_MS = 45_000
const DRIVER_BUNDLE_IDENTIFIER = 'com.linearlite.codex-desktop-driver'

export interface DesktopLaunchRequest {
  appBundleIdentifier: string
  projectDirectory: string
  worktreePath: string
  branchName: string
  prompt: string
  activationMarker: string
  visualActivationMarker: string
  timeoutSeconds: number
}

interface DesktopDriverResponse { status: 'sent' | 'authorized' | 'failed'; errorCode?: string }

export class CodexDesktopDriver {
  constructor(private readonly stateDirectory: string, private readonly appBundleIdentifier: string, private readonly launchTimeoutSeconds: number) {}

  async launch(request: Omit<DesktopLaunchRequest, 'appBundleIdentifier' | 'timeoutSeconds'>): Promise<void> {
    const payload: DesktopLaunchRequest = { ...request, appBundleIdentifier: this.appBundleIdentifier, timeoutSeconds: this.launchTimeoutSeconds }
    try {
      const response = await this.runApplication('launch', [Buffer.from(JSON.stringify(payload)).toString('base64url')], DRIVER_TIMEOUT_MS)
      if (response.status !== 'sent') throw new Error(response.errorCode ?? 'CODEX_DESKTOP_DRIVER_FAILED')
    } catch (error) {
      throw new Error(`CODEX_DESKTOP_LAUNCH_FAILED: ${commandFailureDetails(error)}`)
    }
  }

  async inspect(): Promise<string> {
    const driver = await this.ensureApplication()
    try {
      const { stdout } = await exec(driver.executable, ['inspect', this.appBundleIdentifier], { timeout: 15_000, maxBuffer: 256 * 1024 })
      return stdout
    } catch (error) {
      throw new Error(`CODEX_DESKTOP_INSPECTION_FAILED: ${commandFailureDetails(error)}`)
    }
  }

  async requestAccessibilityAuthorization(): Promise<void> {
    try {
      const response = await this.runApplication('authorize', [], 15_000)
      if (response.status !== 'authorized') throw new Error(response.errorCode ?? 'CODEX_DESKTOP_DRIVER_FAILED')
    } catch (error) {
      throw new Error(commandFailureDetails(error))
    }
  }

  private async runApplication(command: 'launch' | 'authorize', payloadArguments: string[], timeout: number): Promise<DesktopDriverResponse> {
    const driver = await this.ensureApplication()
    const responseDirectory = join(this.stateDirectory, 'apps', 'responses')
    const responsePath = join(responseDirectory, `${randomUUID()}.json`)
    await mkdir(responseDirectory, { recursive: true })
    try {
      let executionError: unknown
      try {
        await exec('/usr/bin/open', ['-W', driver.application, '--args', command, ...payloadArguments, responsePath], { timeout, maxBuffer: 16 * 1024 })
      } catch (error) {
        executionError = error
      }
      try {
        return JSON.parse(await readFile(responsePath, 'utf8')) as DesktopDriverResponse
      } catch (error) {
        throw executionError ?? error
      }
    } finally {
      await unlink(responsePath).catch(() => undefined)
    }
  }

  private async ensureApplication(): Promise<{ application: string; executable: string }> {
    const source = join(dirname(fileURLToPath(import.meta.url)), 'native', 'LinearLiteCodexDesktopDriver.swift')
    const sourceText = await readFile(source)
    const digest = createHash('sha256').update(sourceText).digest('hex').slice(0, 16)
    const application = join(this.stateDirectory, 'apps', 'LinearLiteCodexDesktopDriver.app')
    const executable = join(application, 'Contents', 'MacOS', 'LinearLiteCodexDesktopDriver')
    const signature = join(application, 'Contents', 'Resources', 'source.sha256')
    try {
      const installedDigest = await readFile(signature, 'utf8')
      await access(executable)
      if (installedDigest === digest) return { application, executable }
    } catch {
      // 首次安装或 Driver 源码更新时继续编译。
    }
    await mkdir(join(application, 'Contents', 'MacOS'), { recursive: true })
    await mkdir(join(application, 'Contents', 'Resources'), { recursive: true })
    await writeFile(join(application, 'Contents', 'Info.plist'), desktopDriverInfoPlist())
    const temporary = `${executable}.next`
    try {
      await exec('swiftc', ['-O', source, '-o', temporary], { timeout: 60_000, maxBuffer: 16 * 1024 })
      await rename(temporary, executable)
      await exec('/usr/bin/codesign', ['--force', '--sign', '-', '--identifier', DRIVER_BUNDLE_IDENTIFIER, '--requirements', `=designated => identifier "${DRIVER_BUNDLE_IDENTIFIER}"`, application], { timeout: 15_000, maxBuffer: 16 * 1024 })
      await writeFile(signature, digest)
      return { application, executable }
    } catch (error) {
      throw new Error(`CODEX_DESKTOP_DRIVER_UNAVAILABLE: ${commandFailureDetails(error)}`)
    }
  }
}

function desktopDriverInfoPlist(): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleDevelopmentRegion</key><string>en</string>
<key>CFBundleExecutable</key><string>LinearLiteCodexDesktopDriver</string>
<key>CFBundleIdentifier</key><string>${DRIVER_BUNDLE_IDENTIFIER}</string>
<key>CFBundleName</key><string>Linear Lite Codex Driver</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>LSUIElement</key><true/>
</dict></plist>`
}

export function commandFailureDetails(error: unknown): string {
  if (typeof error === 'object' && error != null && 'stderr' in error && typeof error.stderr === 'string' && error.stderr.trim()) return error.stderr.trim()
  const message = error instanceof Error ? error.message : String(error)
  return message.match(/CODEX_[A-Z_]+/)?.[0] ?? message
}
