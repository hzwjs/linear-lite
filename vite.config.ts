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
  if (/[\\/]node_modules[\\/](react|react-dom|scheduler|veaury|@blocknote|@mantine|prosemirror|yjs|lib0)[\\/]/.test(id)) {
    return 'vendor-editor'
  }
  if (/[\\/]node_modules[\\/](mermaid|@mermaid-js|cytoscape|d3|dagre|dagre-d3-es|roughjs|katex)[\\/]/.test(id)) {
    return 'vendor-diagrams'
  }
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
