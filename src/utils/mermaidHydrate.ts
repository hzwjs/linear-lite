let initialized = false

/**
 * 在已插入 DOM 的容器内，将 `.mermaid` 占位节点交给 mermaid 渲染为 SVG。
 * 仅处理未带 `data-processed` 的节点；与 v-html 更新配合使用。
 */
export async function runMermaidIn(container: Element | null | undefined): Promise<void> {
  if (!container || typeof document === 'undefined') return
  const nodes = Array.from(container.querySelectorAll<HTMLElement>('.mermaid:not([data-processed])'))
  if (nodes.length === 0) return

  const mermaid = (await import('mermaid')).default
  if (!initialized) {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: 'neutral',
      fontFamily: 'inherit',
    })
    initialized = true
  }

  await mermaid.run({ nodes, suppressErrors: true })
}
