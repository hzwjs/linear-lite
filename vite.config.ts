import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import react from '@vitejs/plugin-react'

// 与 linear-lite-server 默认 SERVER_PORT=9080 对齐；后端若用其他端口可设 VITE_API_PROXY_TARGET
const apiProxyTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:9080'

function vendorChunkName(id: string): string | undefined {
  if (!id.includes('node_modules')) return undefined
  if (/[\\/]node_modules[\\/](vue|@vue|vue-router|pinia|vue-i18n|@intlify)[\\/]/.test(id)) {
    return 'vendor-vue'
  }
  // 编辑器依赖只在任务/文档编辑器分片中使用，不能集中成入口的公共 vendor。
  // 否则 Vite 会为动态路由生成 modulepreload，导致首屏下载 1.6MB 编辑器包。
  // Mermaid 只在文档/任务描述中渲染；保持其依赖随编辑器分片加载，避免成为首屏公共 preload。
  if (/[\\/]node_modules[\\/](xlsx)[\\/]/.test(id)) {
    return 'vendor-import'
  }
  if (/[\\/]node_modules[\\/](frappe-gantt)[\\/]/.test(id)) {
    return 'vendor-gantt'
  }
  if (/[\\/]node_modules[\\/](axios|dompurify|marked|lucide-vue-next|photoswipe)[\\/]/.test(id)) {
    return 'vendor-core'
  }
  return undefined
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), react()],
  build: {
    target: 'esnext',
    modulePreload: {
      polyfill: false
    },
    rollupOptions: {
      output: {
        manualChunks: vendorChunkName
      }
    }
  },
  server: {
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true
      }
    }
  }
})
