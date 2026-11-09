import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('mobile pages share one centered width-constrained frame', () => {
  const layout = read('layout/index.vue')
  assert.match(layout, /class="layout-page-frame"/)
  assert.match(layout, /\.layout-page-frame \{ width:100%; max-width:100%; margin:0 auto;/)
  assert.match(layout, /\.layout-main \{\s+width: 100%;\s+margin: 0;/)
})

test('mobile tabs wrap instead of extending outside the content column', () => {
  const responsive = read('styles/responsive.css')
  assert.match(responsive, /\.layout-main \.el-tabs__nav \{[\s\S]*flex-wrap: wrap;/)
  assert.match(responsive, /\.layout-main \.el-tabs__nav-prev,[\s\S]*\.layout-main \.el-tabs__nav-next,[\s\S]*\.layout-main \.el-tabs__active-bar \{\s+display: none;/)
})
