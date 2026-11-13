import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'

test('staff theme shares the customer soft-tactile palette', async () => {
  const css = await readFile(new URL('../src/styles/neumorphism.css', import.meta.url), 'utf8')
  for (const token of [
    '--el-color-primary: #587bd4',
    '--app-bg: #e9eef5',
    '--app-surface: #edf1f7',
    '--app-primary: #587bd4'
  ]) {
    assert.ok(css.includes(token), '管理端主题缺少：' + token)
  }
  assert.ok(css.includes('linear-gradient(145deg, #6888da, #4b70c9)'))
  assert.ok(css.includes('radial-gradient(circle at 9% 16%'))
})
