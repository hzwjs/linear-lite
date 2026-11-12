import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { verifyDistribution } from './distribution-manifest.mjs'

const hostDirectory = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const image = path.join(path.dirname(hostDirectory), 'docs', '交付产物', 'Linear-Lite协作宿主-macOS-arm64-V3.1.0.dmg')

try {
  const metadata = await verifyDistribution({ hostDirectory, image, manifestPath: `${image}.json` })
  console.log(`宿主安装包校验通过，版本 ${metadata.packageVersion}，协议 ${metadata.protocolVersion}`)
} catch (error) {
  console.error(error instanceof Error ? error.message : '宿主安装包校验失败')
  process.exitCode = 1
}
