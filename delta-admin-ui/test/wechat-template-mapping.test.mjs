import test from 'node:test'
import assert from 'node:assert/strict'
import { suggestWechatFields } from '../src/views/wechat/template-mapping.js'

test('maps only recognized source and actual template key; unknown fields require operator input', () => {
  const suggestions = [{type:'thing',source:'productName',label:'服务名称'},
    {type:'character_string',source:'orderNo',label:'订单编号'},{type:'thing',source:'status',label:'业务状态'}]
  assert.deepEqual(suggestWechatFields('服务名称：{{thing5.DATA}}\n订单编号：{{character_string2.DATA}}\n审核结果：{{thing6.DATA}}\n备注：{{thing9.DATA}}',
    suggestions), [{key:'thing5',source:'productName'},{key:'character_string2',source:'orderNo'},
    {key:'thing6',source:'status'},{key:'thing9',source:''}])
  assert.deepEqual(suggestWechatFields('订单编号:{{thing3.DATA}}', suggestions), [{key:'thing3',source:''}])
})
