import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('Codex Desktop Driver launch contract', () => {
  it('registers the repository root and never changes worktree through the UI', async () => {
    const source = await readFile(join(process.cwd(), 'native', 'LinearLiteCodexDesktopDriver.swift'), 'utf8')
    const launch = source.slice(source.indexOf('func launch('), source.indexOf('func inspect('))

    expect(launch).toContain('request.projectDirectory.addingPercentEncoding')
    expect(launch).not.toContain('request.worktreePath.addingPercentEncoding')
    expect(launch).not.toContain('selectWorktree')
    expect(source).not.toContain('func selectWorktree(')
    expect(source).not.toContain('CODEX_WORKTREE_SELECTOR_NOT_FOUND')
    expect(source).not.toContain('CODEX_WORKTREE_NOT_FOUND')
  })
})
