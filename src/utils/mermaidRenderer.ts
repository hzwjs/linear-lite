import { normalizeMermaidDiagramSource } from './mermaidSource'
import mermaid from 'mermaid'

type MermaidApi = {
  initialize: (config: Record<string, unknown>) => void
  render: (id: string, source: string) => Promise<{ svg: string; bindFunctions?: (el: Element) => void }>
}

type RenderOptions = {
  id: string
  loadMermaid?: () => Promise<MermaidApi>
}

let initialized = false

async function defaultLoadMermaid(): Promise<MermaidApi> {
  return mermaid as MermaidApi
}

export function normalizeMermaidRenderError(error: unknown): string {
  if (error instanceof Error && error.message.trim()) return error.message.trim()
  if (typeof error === 'string' && error.trim()) return error.trim()
  return 'Mermaid diagram failed to render.'
}

export async function renderMermaidSvg(source: string, options: RenderOptions) {
  const renderJob = (async () => {
    const mermaid = await (options.loadMermaid ?? defaultLoadMermaid)()
    if (!initialized) {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        theme: 'neutral',
        fontFamily: 'inherit',
      })
      initialized = true
    }

    return mermaid.render(options.id, normalizeMermaidDiagramSource(source))
  })()

  return Promise.race([
    renderJob,
    new Promise<never>((_, reject) => {
      globalThis.setTimeout(() => reject(new Error('Mermaid render timed out.')), 5000)
    })
  ])
}

export function resetMermaidRendererForTests() {
  initialized = false
}
