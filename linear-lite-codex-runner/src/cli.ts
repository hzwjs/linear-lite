import { loadConfig } from './config.js'
import { runOnce } from './runLoop.js'
import { CodexDesktopDriver } from './codexDesktopDriver.js'

const configPath = process.argv[2]
if (!configPath) throw new Error('用法: npm start -- /absolute/path/to/runner.json')
const config = await loadConfig(configPath)
if (process.argv[3] === 'inspect-desktop') {
  process.stdout.write(await new CodexDesktopDriver(config.stateDirectory, config.codexDesktopAppBundleIdentifier, config.codexDesktopLaunchTimeoutSeconds).inspect())
  process.exit(0)
}
if (process.argv[3] === 'request-desktop-access') {
  await new CodexDesktopDriver(config.stateDirectory, config.codexDesktopAppBundleIdentifier, config.codexDesktopLaunchTimeoutSeconds).requestAccessibilityAuthorization()
  process.exit(0)
}
for (;;) {
  try { await runOnce(config) } catch (error) { console.error('Runner 循环失败', error) }
  await new Promise((resolve) => setTimeout(resolve, 5000))
}
