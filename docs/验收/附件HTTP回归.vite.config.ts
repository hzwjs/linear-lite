import { defineConfig, mergeConfig } from 'vite'
import productConfig from '../../vite.config'

// 局域网 HTTP 验收复用产品配置；代理 Origin 与目标同源，避免开发地址触发生产 CORS 拒绝。
const target = process.env.VITE_API_PROXY_TARGET
if (!target) throw new Error('VITE_API_PROXY_TARGET is required for HTTP regression acceptance')
export default mergeConfig(productConfig, defineConfig({
  server: { proxy: { '/api': { headers: { Origin: new URL(target).origin } } } },
}))
