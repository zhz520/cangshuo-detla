import assert from 'node:assert/strict'
import test from 'node:test'
import { ref } from 'vue'
import { setImmediate } from 'node:timers/promises'
import { readFile } from 'node:fs/promises'
import { useAvatarUpload } from '../composables/useAvatarUpload.js'
import { usePagedList } from '../composables/usePagedList.js'

test('both avatar entry points share a local guard; cancel/error preserve the old URL and unlock retry', async () => {
  const page=await readFile(new URL('../pages/mine/profile.vue',import.meta.url),'utf8')
  assert.doesNotMatch(page,/uni\.(?:show|hide)Loading/)
  assert.match(page,/changeAvatar\(\(\) => upload\(tempUrl/)
  assert.match(page,/changeAvatar\(async \(\) => \(await chooseAndUpload\(1/)
  const avatar=ref('old'),saving=ref(false), u=useAvatarUpload(avatar,saving)
  let finish, calls=0
  const upload=()=>{calls++; return new Promise(r=>{finish=r})}
  const job=u.changeAvatar(upload)
  await u.changeAvatar(upload); assert.equal(calls,1); assert.equal(u.uploading.value,true)
  finish('new'); await job; assert.equal(avatar.value,'new')
  await u.changeAvatar(async()=>{throw {errMsg:'chooseImage:fail cancel'}})
  assert.equal(u.uploadError.value,''); assert.equal(u.uploading.value,false)
  await u.changeAvatar(async()=>{throw Error('offline')})
  assert.ok(u.uploadError.value); assert.equal(avatar.value,'new'); assert.equal(u.uploading.value,false)
  saving.value=true; await u.changeAvatar(upload); assert.equal(calls,1)
  saving.value=false; await u.changeAvatar(async()=>'retried'); assert.equal(avatar.value,'retried'); assert.equal(u.uploadError.value,'')
})

test('product query refresh keeps cards, ignores old append, retries exact failed query/page without mixing results', async () => {
  const pending=[]
  const p=usePagedList({pageSize:1,retainOnQueryChange:true,fetchPage:args=>new Promise((resolve,reject)=>pending.push({args,resolve,reject}))})
  let job=p.setQuery('A'); await setImmediate()
  pending[0].resolve({records:[{id:1}],total:2}); await job
  const old=p.loadMore(); await setImmediate()
  job=p.setQuery('B'); await setImmediate()
  assert.equal(p.phase.value,'refresh'); assert.deepEqual(p.items.value,[{id:1}])
  pending[2].reject(Error('offline')); await job
  pending[1].resolve({records:[{id:100}],total:2}); await old
  assert.ok(p.error.value); assert.deepEqual(p.items.value,[{id:1}]); assert.equal(await p.loadMore(),false)
  job=p.retry(); await setImmediate(); assert.equal(pending[3].args.query,'B'); assert.equal(pending[3].args.pageNum,1)
  pending[3].resolve({records:[{id:2}],total:2}); await job; assert.deepEqual(p.items.value,[{id:2}])
  job=p.loadMore(); await setImmediate(); pending[4].reject(Error('offline')); await job
  job=p.retry(); await setImmediate(); assert.equal(pending[5].args.pageNum,2); assert.equal(p.phase.value,'more')
  pending[5].resolve({records:[{id:3}],total:2}); await job; assert.deepEqual(p.items.value,[{id:2},{id:3}])
})
