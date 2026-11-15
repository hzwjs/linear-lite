import { describe, expect, it, vi } from 'vitest'

const state = vi.hoisted(() => {
  let ready!: () => void
  const promise = new Promise<void>((resolve) => { ready = resolve })
  const app = { use: vi.fn(), mount: vi.fn() }
  app.use.mockReturnValue(app)
  return { app, ready, promise }
})

vi.mock('vue', () => ({ createApp: () => state.app }))
vi.mock('pinia', () => ({ createPinia: () => ({}) }))
vi.mock('./AdaptiveRoot.vue', () => ({ default: {} }))
vi.mock('./router', () => ({ default: { isReady: () => state.promise }, setupRouterGuards: vi.fn() }))
vi.mock('./i18n', () => ({ i18n: {} }))
vi.mock('./store/localeStore', () => ({ useLocaleStore: vi.fn() }))
vi.mock('./store/authStore', () => ({ useAuthStore: () => ({ ensureDocumentAssetSession: vi.fn() }) }))

describe('application startup', () => {
  it('waits for the initial route before rendering the application', async () => {
    await import('./main')
    expect(state.app.mount).not.toHaveBeenCalled()
    state.ready()
    await state.promise
    await Promise.resolve()
    expect(state.app.mount).toHaveBeenCalledWith('#app')
  })
})
