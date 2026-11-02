import test from 'node:test'
import assert from 'node:assert/strict'
import {defaultUpdateSelection,toggleUpdateSelection,publicReleaseNotesUrl} from '../src/utils/update-selection.js'

const releases=[{version:'1.3.2',items:[
  {id:'security',name:'安全修复',component:'backend',required:true},
  {id:'navigation',name:'菜单整理',component:'admin'},
  {id:'reports',name:'收益报表',component:'admin',requires:['navigation']}
]}]

test('optional update items can be selected and cancelled independently',()=>{
  assert.deepEqual(defaultUpdateSelection(releases),['1.3.2:security','1.3.2:navigation','1.3.2:reports'])
  let selected=toggleUpdateSelection(releases,[],releases[0],releases[0].items[1],true)
  assert.deepEqual(selected,['1.3.2:navigation'])
  selected=toggleUpdateSelection(releases,selected,releases[0],releases[0].items[1],false)
  assert.deepEqual(selected,[])
  selected=toggleUpdateSelection(releases,[],releases[0],releases[0].items[2],true)
  assert.deepEqual(selected,['1.3.2:navigation','1.3.2:reports'])
})

test('cancelling a dependency removes optional dependants and keeps required items fixed',()=>{
  let selected=['1.3.2:security','1.3.2:navigation','1.3.2:reports']
  selected=toggleUpdateSelection(releases,selected,releases[0],releases[0].items[1],false)
  assert.deepEqual(selected,['1.3.2:security'])
  selected=toggleUpdateSelection(releases,selected,releases[0],releases[0].items[0],false)
  assert.deepEqual(selected,['1.3.2:security'])
})

test('only authorization-center release note links are exposed to customers',()=>{
  assert.equal(publicReleaseNotesUrl('https://jgsapi.example.com/public/release-notes/detla/1.3.2'),'https://jgsapi.example.com/public/release-notes/detla/1.3.2')
  assert.equal(publicReleaseNotesUrl('https://github.com/zhz520/cangshuo-detla/releases/tag/v1.3.1'),'')
  assert.equal(publicReleaseNotesUrl('/public/release-notes/detla/1.3.2'),'')
})
