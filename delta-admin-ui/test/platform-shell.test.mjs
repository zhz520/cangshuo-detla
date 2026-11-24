import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('staff avatars prefer uploaded avatars, then the configured brand logo', () => {
  const avatar = read('components/StaffAvatar.vue')
  assert.match(avatar, /userStore\.avatar \|\| siteStore\.logo/)
  assert.match(avatar, /thumbnailUrl\(source\.value/)
  assert.match(avatar, /brandInitial/)
  assert.match(avatar, /@error="failed = true"/)
  for (const file of ['layout/H5Layout.vue', 'layout/index.vue', 'views/dashboard/MobileDashboard.vue', 'views/wechat/Binding.vue']) {
    assert.match(read(file), /StaffAvatar/)
  }
})

test('H5 token expiry and maintenance redirects stay inside the H5 shell', () => {
  const request = read('utils/request.js')
  assert.match(request, /function loginTarget\(\)/)
  assert.match(request, /path: isH5Path\(current\.path\) \? '\/h5\/login' : '\/login'/)
  assert.match(request, /'\/h5\/platform\/updates'/)
})

test('client platform detection and shared loading/motion styles are installed', () => {
  const platform = read('utils/client-platform.js')
  const main = read('main.js')
  const style = read('style.css')
  assert.match(platform, /micromessenger/)
  assert.match(platform, /mqqbrowser/)
  assert.match(platform, /dataset\.os/)
  assert.match(main, /installClientPlatform\(\)/)
  assert.match(style, /viewport-enter-active[^{]*\{[^}]*var\(--view-time\)/)
  for (const token of ['enter','back','tab']) assert.ok(style.includes('--view-time: var(--delta-motion-' + token))
  assert.match(style, /html\[data-client='wechat'\]/)
  assert.match(style, /\.el-loading-mask \{ background-color:/)
})
