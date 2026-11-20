import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const root = process.cwd()
const read = (name) => readFileSync(join(root, 'src/components', name), 'utf8')

const components = {
  'FormSection.vue': read('FormSection.vue'),
  'AsyncState.vue': read('AsyncState.vue'),
  'DataTable.vue': read('DataTable.vue'),
  'PageHeader.vue': read('PageHeader.vue')
}

test('design-system components use theme tokens instead of fixed colors', () => {
  for (const [name, text] of Object.entries(components)) {
    assert.doesNotMatch(text, /#[0-9a-fA-F]{3,8}\b/, `${name} must not hard-code hex colors`)
    assert.doesNotMatch(text, /rgba?\(/, `${name} must not hard-code rgb colors`)
    assert.match(text, /var\(--el-/, `${name} must use Element Plus theme tokens`)
  }
})

test('FormSection keeps a title, description and action slot', () => {
  const text = components['FormSection.vue']
  assert.match(text, /class="form-section-title"/)
  assert.match(text, /class="form-section-description"/)
  assert.match(text, /<slot name="actions"/)
  assert.match(text, /<slot \/>/)
})

test('AsyncState exposes loading, empty, error and retry states accessibly', () => {
  const text = components['AsyncState.vue']
  assert.match(text, /aria-busy/)
  assert.match(text, /role="alert"/)
  assert.match(text, /role="status"/)
  assert.match(text, /@click="\$emit\('retry'\)"/)
  assert.match(text, /name="empty"/)
  assert.match(text, /name="error"/)
  assert.match(text, /prefers-reduced-motion/)
})

test('DataTable keeps Element Plus column slots and a themed empty state', () => {
  const text = components['DataTable.vue']
  assert.match(text, /v-bind="\$attrs"/)
  assert.match(text, /<slot name="empty">/)
  assert.match(text, /delta-data-table-empty/)
  assert.match(text, /v-loading="loading"/)
  assert.match(text, /var\(--el-fill-color-light\)/)
})





test('migrated pages do not regress to fixed colors', () => {
  const pages = [
    'src/views/player/PlayerLevels.vue',
    'src/views/player/PlayerList.vue',
    'src/views/player/Governance.vue',
    'src/views/statistics/SettlementLedger.vue',
    'src/views/system/StaffRoles.vue',
    'src/views/system/WechatNotices.vue'
  ]
  for (const page of pages) {
    const text = readFileSync(join(root, page), 'utf8')
    assert.doesNotMatch(text, /#[0-9a-fA-F]{3,8}\b/, `${page} must use theme tokens, not hex colors`)
  }
})



