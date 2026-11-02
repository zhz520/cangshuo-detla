import test from 'node:test'
import assert from 'node:assert/strict'
import {applicableUpdateReleases,defaultUpdateSelection,toggleUpdateSelection,publicReleaseNotesUrl,isUpdateItemInstalled,compareReleaseVersions} from '../src/utils/update-selection.js'

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

test('stale cached releases equal to the current version are not shown as updates',()=>{
  const releases=[{version:'1.3.5'},{version:'1.3.6'},{version:'broken'}]
  assert.deepEqual(applicableUpdateReleases(releases,'1.3.5').map(row=>row.version),['1.3.6'])
  assert.deepEqual(applicableUpdateReleases(releases,'1.3.6'),[])
})

test('partial upgrades keep lagging components visible even when the overall release is current', () => {
  const release = { version: '1.4.1', items: [{ id: 'ui', component: 'admin' }, { id: 'mobile', component: 'h5' }] }
  const versions = { release: '1.4.1', backend: '1.4.1', admin: '1.4.1', h5: '1.4.0' }
  assert.deepEqual(applicableUpdateReleases([release], '1.4.1', versions), [release])
  assert.equal(isUpdateItemInstalled(release, release.items[0], versions), true)
  assert.equal(isUpdateItemInstalled(release, release.items[1], versions), false)
  assert.deepEqual(applicableUpdateReleases([release], '1.4.1', { ...versions, h5: '1.4.1' }), [])
})

test('deployed component versions override historical installed items after a rollback', () => {
  const release = { version: '1.4.1', items: [{ id: 'ui', component: 'admin' }] }
  assert.equal(isUpdateItemInstalled(release, release.items[0], { admin: '1.4.0' }, ['1.4.1:ui']), false)
  assert.equal(isUpdateItemInstalled(release, release.items[0], {}, ['1.4.1:ui']), true)
  assert.deepEqual(applicableUpdateReleases([release], '1.4.1', { admin: '1.4.0' }, ['1.4.1:ui']), [release])
})

test('malformed and preview versions are not treated as formal releases', () => {
  assert.equal(compareReleaseVersions('1.4.2-preview', '1.4.1'), 0)
  assert.deepEqual(applicableUpdateReleases([{ version: '1.4.2-preview' }], '1.4.1'), [])
})
