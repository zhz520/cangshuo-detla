import test from 'node:test'
import assert from 'node:assert/strict'
import { menuErrors, moveMenuButton, newMenuButton, readMenuButtons, writeMenuButtons } from '../src/views/wechat/menu-model.js'

test('round trips two-level menu in order without UI keys or unused action fields', () => {
  const wire = { button: [
    { name: '商城', sub_button: [{ name: '首页', type: 'view', url: 'https://example.com/' }, { name: '帮助', type: 'click', key: 'help' }] },
    { name: '扫码', type: 'scancode_push', key: 'scan' }
  ] }
  const roots = readMenuButtons(wire)
  assert.equal(menuErrors(roots).size, 0)
  roots[0].children[1].url = 'unused'
  assert.deepEqual(writeMenuButtons(roots), wire)
  assert.equal(moveMenuButton(roots[0].children, 0, 1), true)
  assert.equal(moveMenuButton(roots, 0, -1), false)
  assert.equal(writeMenuButtons(roots).button[0].sub_button[0].name, '帮助')
})

test('shows issues for byte limits, hidden third levels, and unknown imported actions', () => {
  const unknown = newMenuButton({ name: '老菜单', type: 'future_vendor_action' })
  const invalid = newMenuButton({ name: '八个汉字名称过长', type: 'miniprogram', url: 'file:///bad', appid: 'invalid' })
  const third = newMenuButton({ name:'一级', sub_button:[{name:'二级', sub_button:[{name:'三级'}]}] })
  const errors = menuErrors([unknown, invalid, third])
  assert.match(errors.get(unknown.id).join(' '), /暂不支持/)
  assert.match(errors.get(invalid.id).join(' '), /16 字节/)
  assert.match(errors.get(invalid.id).join(' '), /AppID/)
  assert.match(errors.get(third.children[0].id).join(' '), /两级/)
})
