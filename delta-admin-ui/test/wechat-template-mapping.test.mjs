import test from 'node:test'
import assert from 'node:assert/strict'
import { existingFieldsForTemplate, suggestWechatFields, suggestWechatTemplate } from '../src/views/wechat/template-mapping.js'

test('an equal variable name in another template does not inherit the previous meaning', () => {
  const fields = [{key:'thing1',source:'orderNo'}]
  const content = '商品名称：{{thing1.DATA}}'
  const suggestions = [{type:'thing',source:'productName',label:'商品名称'}]
  assert.deepEqual(suggestWechatFields(content, suggestions, existingFieldsForTemplate('old','old',fields)), fields)
  assert.deepEqual(suggestWechatFields(content, suggestions, existingFieldsForTemplate('new','old',fields)), [{key:'thing1',source:'productName'}])
})

test('maps only recognized source and actual template key; unknown fields require operator input', () => {
  const suggestions = [{type:'thing',source:'productName',label:'服务名称'},
    {type:'character_string',source:'orderNo',label:'订单编号'},{type:'thing',source:'status',label:'业务状态'}]
  assert.deepEqual(suggestWechatFields('服务名称：{{thing5.DATA}}\n订单编号：{{character_string2.DATA}}\n审核结果：{{thing6.DATA}}\n备注：{{thing9.DATA}}',
    suggestions), [{key:'thing5',source:'productName'},{key:'character_string2',source:'orderNo'},
    {key:'thing6',source:'status'},{key:'thing9',source:''}])
  assert.deepEqual(suggestWechatFields('订单编号:{{thing3.DATA}}', suggestions), [{key:'thing3',source:''}])
})

test('only preselects a unique exact title with all fields matched in the current catalog', () => {
  const scheme = { name: '新订单待处理', templateHint: '搜索关键词：新订单、订单待处理',
    fieldSuggestions: [{ type: 'character_string', source: 'orderNo', label: '订单编号' }] }
  const match = { templateId: 'owned-template', title: '订单待处理', content: '订单编号：{{character_string2.DATA}}' }
  assert.deepEqual(suggestWechatTemplate([match], scheme),
    { templateId: 'owned-template', fields: [{ key: 'character_string2', source: 'orderNo' }] })
  assert.equal(suggestWechatTemplate([], scheme), null)
  assert.equal(suggestWechatTemplate([{ ...match, title: '订单待处理提醒模板' }], scheme), null)
  assert.equal(suggestWechatTemplate([match, { ...match, templateId: 'duplicate-title' }], scheme), null)
  assert.equal(suggestWechatTemplate([{ ...match, content: match.content + '\n备注：{{thing1.DATA}}' }], scheme), null)
})
