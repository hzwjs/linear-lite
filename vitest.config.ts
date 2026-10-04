import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

export default mergeConfig(viteConfig, defineConfig({
  test: {
    globals: true,
    environment: 'jsdom',
    // 编辑器与 ProseMirror 通过 Vite 的 dedupe 共用实例，避免 Node 外部加载再注册 Selection。
    server: { deps: { inline: [/@blocknote\//, /@tiptap\//, /prosemirror-/] } }
  }
}))
