import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const detail = await readFile(new URL('../pages/product/detail.vue', import.meta.url), 'utf8')
const tabbar = await readFile(new URL('../components/CustomTabBar.vue', import.meta.url), 'utf8')

test('product detail shows the short service content and the rich detail separately', () => {
  assert.match(detail, /serviceContentText/)
  assert.match(detail, /product\.value\?\.detail \|\| product\.value\?\.description/)
  assert.doesNotMatch(detail, /descriptionText/)
  assert.match(detail, /\{\{ serviceContentText \|\| '以本页服务说明为准' \}\}/)
})

test('tabbar page lock is acquired once and released on every exit path', () => {
  assert.match(tabbar, /let scrollLocked = false/)
  assert.match(tabbar, /function acquireScrollLock\(\) \{\s*if \(scrollLocked\) return/)
  assert.match(tabbar, /function releaseScrollLock\(\) \{\s*if \(!scrollLocked\) return/)
  assert.match(tabbar, /onMounted\(\(\) => \{[\s\S]*?acquireScrollLock\(\)/)
  assert.match(tabbar, /onShow\(\(\) => \{\s*acquireScrollLock\(\)/)
  assert.match(tabbar, /onHide\(\(\) => \{\s*releaseScrollLock\(\)/)
  assert.match(tabbar, /onUnmounted\(\(\) => \{\s*releaseScrollLock\(\)/)
})
