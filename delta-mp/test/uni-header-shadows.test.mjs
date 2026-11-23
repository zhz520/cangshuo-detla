import test from 'node:test'
import assert from 'node:assert/strict'
import postcss from 'postcss'
import path from 'node:path'
import {localHeaderShadows} from '../scripts/uni-header-shadows.mjs'

const framework = path.resolve('node_modules/@dcloudio/uni-h5/style/framework')
const run = async (from,css) => (await postcss([localHeaderShadows()]).process(css,{from})).css
test('drop only the framework image preloader, not application body decorations',async()=>{
  const src='body::after{content:"";animation:shadow-preload .1s}@keyframes shadow-preload{from{opacity:1}}.other{color:red}'
  assert.equal(await run(path.join(framework,'shadow.css'),src),'.other{color:red}')
  assert.equal(await run(path.resolve('styles/shadow.css'),src),src)
})
test('preserve framework pseudo-element geometry and shade only the original six header variants',async()=>{
  const rules='.uni-page-head-shadow::after{height:5px;top:100%}.uni-page-head-shadow-grey::after{background-image:url(https://cdn.dcloud.net.cn/img/shadow-grey.png)}.other{background-image:url(local.png)}'
  const out=await run(path.join(framework,'pageHead.css'),rules)
  assert.match(out,/height:5px;top:100%/); assert.match(out,/linear-gradient/); assert.match(out,/url\(local.png\)/)
  assert.doesNotMatch(out,/cdn\.dcloud/)
})
