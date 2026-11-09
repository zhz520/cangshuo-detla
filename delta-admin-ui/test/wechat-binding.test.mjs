import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('staff WeChat binding has one desktop and one mobile account entry', () => {
  const layout = read('layout/index.vue')
  const navigation = read('utils/navigation.js')

  assert.doesNotMatch(navigation, /path:\s*['"]\/wechat-binding['"]/)
  assert.match(layout, /<el-dropdown-item v-if="!isCompact" command="wechat">公众号消息通知<\/el-dropdown-item>/)
  assert.match(layout, /<button type="button" @click="accountAction\('wechat'\)"><span>公众号消息通知<\/span>/)
  assert.equal((layout.match(/公众号消息通知/g) || []).length, 2)
})

test('binding screen states the global one-WeChat rule and uses one primary guide', () => {
  const binding = read('views/wechat/Binding.vue')

  assert.match(binding, /一个微信只能绑定一个账号/)
  assert.match(binding, /复制微信打开地址/)
  assert.match(binding, /绑定当前微信/)
  assert.match(binding, /templateReady/)
  assert.doesNotMatch(binding, /class="binding-steps"/)
  assert.doesNotMatch(binding, /<li><span>1<\/span>/)
})
