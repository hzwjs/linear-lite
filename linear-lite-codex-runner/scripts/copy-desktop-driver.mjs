import { cp, mkdir, rm } from 'node:fs/promises'

await rm('dist/native', { recursive: true, force: true })
await mkdir('dist/native', { recursive: true })
await cp('native/LinearLiteCodexDesktopDriver.swift', 'dist/native/LinearLiteCodexDesktopDriver.swift')
