import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 与 linear-lite-server 默认 SERVER_PORT=9080 对齐；后端若用其他端口可设 VITE_API_PROXY_TARGET
const apiProxyTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:9080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true
      }
    }
  }
})
