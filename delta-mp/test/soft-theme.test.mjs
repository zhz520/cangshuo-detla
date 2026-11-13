import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'

const theme = await readFile(new URL('../styles/neumorphism.scss', import.meta.url), 'utf8')

test('soft theme keeps the reference palette and depth tokens', () => {
  for (const token of [
    '--neo-canvas: #e9eef5',
    '--neo-surface: #edf1f7',
    '--neo-ink: #263449',
    '--neo-accent: #587bd4',
    '--neo-accent-deep: #3f62b9',
    '--neo-price: #c96f5f'
  ]) {
    assert.ok(theme.includes(token), '缺少设计令牌：' + token)
  }
  assert.ok(theme.includes('--neo-shadow:'), '缺少浮起卡片阴影')
  assert.ok(theme.includes('--neo-inset:'), '缺少内凹输入阴影')
})

test('soft theme styles every customer H5 surface and shared control', () => {
  for (const selector of [
    '.home-page',
    '.category-page',
    '.product-detail',
    '.directory-page',
    '.mine-page',
    '.custom-tabbar',
    '.product-card',
    '.product-row',
    '.profile-card',
    '.service-panel',
    '.bottom-bar',
    '.search-inner',
    '.btn-buy',
    '.notice-list-popup'
  ]) {
    assert.ok(theme.includes(selector), '主题未覆盖：' + selector)
  }
})

