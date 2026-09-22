import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { windowsTaskCreateArgs } from '../scripts/service.mjs'

test('Windows installer uses an encoding-safe PowerShell script', async () => {
  const source = await readFile(new URL('../install/windows/install.ps1', import.meta.url))
  assert.equal(
    source.some((byte) => byte > 0x7f),
    false,
    'Windows PowerShell 5.1 must parse the installer without a UTF-8 BOM',
  )
})

test('Windows task registration passes the launcher path as one argument', () => {
  const launcherPath = 'C:\\Users\\Test User\\AppData\\Local\\Linear Lite\\Pi Bridge\\runtime\\launch.cmd'
  const args = windowsTaskCreateArgs('Linear Lite Pi Bridge', launcherPath)

  assert.equal(args[args.indexOf('/TR') + 1], launcherPath)
  assert.equal(args.some((argument) => argument.includes('"')), false)
})
