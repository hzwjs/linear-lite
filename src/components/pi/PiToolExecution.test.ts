import { createApp, defineComponent, nextTick, reactive } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import type { RuntimeDisplayBlock } from '../../services/api/agent'
import PiToolExecution from './PiToolExecution.vue'

const mounted: Array<() => void> = []

function mountTool(block: RuntimeDisplayBlock) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const state = reactive({ block })
  const app = createApp(defineComponent({
    components: { PiToolExecution },
    setup: () => ({ state }),
    template: '<PiToolExecution :block="state.block" />'
  }))
  app.mount(host)
  mounted.push(() => {
    app.unmount()
    host.remove()
  })
  return { host, state }
}

function toolBlock(phase: 'streaming' | 'error', output: string): RuntimeDisplayBlock {
  return {
    executionId: 'exec-1',
    jobId: 1,
    blockId: 'call-1',
    revision: phase === 'streaming' ? 2 : 3,
    kind: 'tool',
    phase,
    content: null,
    tool: {
      name: 'bash',
      arguments: { command: 'rg -n "RuntimeDisplayBlock" src' },
      content: [{ type: 'text', text: output }],
      isError: phase === 'error',
      truncation: null
    },
    createdAt: '2026-08-18T00:00:00'
  }
}

afterEach(() => {
  mounted.splice(0).forEach((unmount) => unmount())
})

describe('PiToolExecution', () => {
  it('renders the real tool name, arguments and accumulated partial output', () => {
    const { host } = mountTool(toolBlock('streaming', 'src/a.ts:1:RuntimeDisplayBlock'))

    expect(host.textContent).toContain('bash')
    expect(host.textContent).toContain('rg -n "RuntimeDisplayBlock" src')
    expect(host.textContent).toContain('src/a.ts:1:RuntimeDisplayBlock')
    expect(host.textContent).not.toContain('工具执行完成')
    expect(host.textContent).not.toContain('工具执行失败')
  })

  it('replaces partial output in the same runtime block', async () => {
    const { host, state } = mountTool(toolBlock('streaming', 'partial output'))
    state.block = { ...toolBlock('streaming', 'final output'), revision: 3 }
    await nextTick()

    expect(host.textContent).not.toContain('partial output')
    expect(host.textContent).toContain('final output')
    expect(host.querySelectorAll('.pi-tool')).toHaveLength(1)
  })

  it('keeps failed output expanded with the returned error body', () => {
    const { host } = mountTool(toolBlock('error', 'permission denied'))

    expect(host.textContent).toContain('permission denied')
    expect(host.textContent).toContain('失败')
  })
})
