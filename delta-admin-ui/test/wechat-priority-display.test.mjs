import test from 'node:test'
import assert from 'node:assert/strict'
import {cardDisplay} from '../src/views/wechat/template-mapping.js'
test('shared card inherits actual key; conflicts are not silently resolved',()=>{
 const row={templateId:'A',content:'产品：{{thing3.DATA}}\n金额：{{amount9.DATA}}'}
 const binding={role:'USER',scenario:'new_order',templateId:'A',enabled:true,fields:{thing3:'productName',amount9:'orderAmount'}}
 const plan={role:'USER',scenario:'new_order',priorityDisplaySource:'productName'}
 assert.equal(cardDisplay(row,[binding],[plan]).inherit,'产品')
 assert.equal(cardDisplay(row,[{...binding,templateId:'B'}],[plan]).inherit,'未指定')
 assert.equal(cardDisplay(row,[binding,{...binding,role:'CS'}],[plan,{...plan,role:'CS',priorityDisplaySource:'orderAmount'}]).inherit,'多个中心方案冲突')
 assert.equal(cardDisplay(row,[binding],[{...plan,priorityDisplaySource:'other'}]).inherit,'中心字段未匹配')
 assert.ok(cardDisplay({...row,priorityDisplayOverride:'thing7'},[],[]).stale)
})
