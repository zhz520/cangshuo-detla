import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const root = process.cwd()
const read = (path) => readFileSync(join(root, path), 'utf8')

test('feedback toast deduplicates and keeps type-specific durations', () => {
  const text = read('src/utils/feedback.js')
  assert.match(text, /DEDUPE_WINDOW = 1500/)
  assert.match(text, /if \(\(recent\.get\(key\) \|\| 0\) > now - DEDUPE_WINDOW\) return/)
  assert.match(text, /type === 'error'\).*5000/s)
  assert.match(text, /type === 'warning'\).*4000/s)
  assert.match(text, /showClose: true/)
  assert.match(text, /grouping: true/)
})

test('confirmAction only resolves true after explicit confirmation', () => {
  const text = read('src/utils/feedback.js')
  assert.match(text, /ElMessageBox\.confirm/)
  assert.match(text, /distinguishCancelAndClose: true/)
  assert.match(text, /catch \(_\) \{\s*return false/s)
})

test('player levels use the shared feedback service', () => {
  const text = read('src/views/player/PlayerLevels.vue')
  assert.match(text, /from '@\/utils\/feedback'/)
  assert.match(text, /toast\.success/)
  assert.match(text, /toast\.warning/)
  assert.match(text, /confirmAction/)
  assert.doesNotMatch(text, /from 'element-plus'/)
})

