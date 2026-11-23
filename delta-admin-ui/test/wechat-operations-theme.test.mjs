import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const pages = [
  'src/views/wechat/Accounts.vue',
  'src/views/wechat/Tools.vue',
  'src/views/wechat/Content.vue',
  'src/views/marketing/FlashSales.vue'
]

test('公众号运营和秒杀后台页面使用统一主题变量', () => {
  for (const file of pages) {
    const source = readFileSync(new URL(`../${file}`, import.meta.url), 'utf8')
    assert.match(source, /var\(--el-/, `${file} must use Element theme variables`)
    assert.doesNotMatch(source, /background\s*:\s*#(?:fff|ffffff|f[0-9a-f]{5})\b/i, `${file} must not hard-code a surface color`)
    assert.doesNotMatch(source, /color\s*:\s*#(?:111|000|1[0-9a-f]{5})\b/i, `${file} must not hard-code primary text color`)
  }
})

test('公众号运营入口保留账号、工具、图文和秒杀路由', () => {
  const router = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
  const navigation = readFileSync(new URL('../src/utils/navigation.js', import.meta.url), 'utf8')
  for (const path of ['/wechat/accounts', '/wechat/tools', '/wechat/content', '/marketing/flash-sales']) {
    assert.match(navigation, new RegExp(path.replaceAll('/', '\\/')))
  }
  assert.match(router, /path:\s*'content'/)
  assert.match(router, /path:\s*'flash-sales'/)
})
