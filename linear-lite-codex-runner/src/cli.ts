import { loadConfig } from './config.js'
import { runOnce } from './runLoop.js'

const configPath = process.argv[2]
if (!configPath) throw new Error('用法: npm start -- /absolute/path/to/runner.json')
const config = await loadConfig(configPath)
for (;;) {
  try { await runOnce(config) } catch (error) { console.error('Runner 循环失败', error) }
  await new Promise((resolve) => setTimeout(resolve, 5000))
}
