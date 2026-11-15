import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const html = readFileSync('index.html', 'utf8')
const template = new DOMParser().parseFromString(html, 'text/html')
const startupScript = template.querySelector('head script')!.textContent!
const startupStyle = template.querySelector('head style')!.textContent!

function startupAt(path: string) {
  const page = new DOMParser().parseFromString(html, 'text/html')
  const location = new URL(path, 'https://example.test')
  const storage = { getItem: () => null }
  new Function('document', 'location', 'localStorage', 'performance', startupScript)(
    page, location, storage, { mark: () => {} }
  )
  return page.documentElement.classList
}

describe('document tab initial layout', () => {
  it.each([
    '/projects/7/documents?documentView=directory',
    '/projects/7/documents/2?documentView=article',
    '/projects/7/documents/2/html'
  ])('does not draw the sidebar placeholders before the application mounts: %s', (path) => {
    expect(startupAt(path).contains('document-tab')).toBe(true)
    expect(startupStyle).toMatch(/html\.document-tab\s+#app:empty::before[\s\S]*?display:\s*none/)
    expect(startupStyle).toMatch(/html\.document-tab\s+#app:empty::after[\s\S]*?display:\s*none/)
  })

  it('retains the normal placeholder for the workspace', () => {
    expect(startupAt('/').contains('document-tab')).toBe(false)
  })
})
