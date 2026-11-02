import test from 'node:test'
import assert from 'node:assert/strict'
import {buildNavigation, activeNavigationMenu} from '../src/utils/navigation.js'

const screen = (path, title, roles = ['admin', 'cs'], extra = {}) => ({path,children:[{path:'',meta:{title,roles,...extra}}]})
const paths = groups => groups.flatMap(group => group.items.flatMap(item => item.children || [item])).map(item => item.path)

test('child permissions and parent roles both restrict navigation without leaving empty groups', () => {
  const routes=[screen('/dashboard','仪表盘'),screen('/order/list','订单列表'),screen('/complaint','投诉处理'),
    screen('/privacy-requests','隐私申请',['admin'],{permission:'user.manage'}),
    {path:'/system',meta:{roles:['admin']},children:[{path:'roles',meta:{title:'角色与权限',roles:['admin','cs']}}]}]
  const visible=buildNavigation(routes,'cs',permission=>permission==='order.read')
  assert.deepEqual(paths(visible),['/dashboard','/order/list'])
  assert(visible.every(group=>group.items.length&&group.items.every(item=>!item.children||item.children.length)))
})

test('grouping retains every visible screen once, excludes hidden pages, and never changes route records', () => {
  const routes=[screen('/dashboard','仪表盘'),screen('/player','打手管理'),screen('/invitations','入驻邀请',['admin','cs'],{permission:'player.manage'}),
    screen('/material-review','主页素材审核',['admin','cs'],{permission:'media.review'}),screen('/future','未来功能'),
    screen('/product/create','新增商品',['admin'],{hidden:true})]
  const before=JSON.stringify(routes),groups=buildNavigation(routes,'admin',()=>true),visible=paths(groups)
  assert.deepEqual(new Set(visible),new Set(['/dashboard','/player','/invitations','/material-review','/future']))
  assert.equal(visible.length,new Set(visible).size)
  assert.equal(groups.find(group=>group.id==='other').items[0].path,'/future')
  assert.equal(JSON.stringify(routes),before)
  assert.equal(activeNavigationMenu('/material-review',groups),'nav-players')
})

test('a limited administrator cannot see finance, chat or configuration entries without their grants', () => {
  const routes=[screen('/order/list','订单列表'),screen('/settlements','自动分账台账'),screen('/rewards','打赏记录'),
    screen('/platform/income-analysis','收益对比分析'),screen('/chat/record','聊天记录'),screen('/system/config','系统配置')]
  assert.deepEqual(paths(buildNavigation(routes,'admin',permission=>permission==='order.read')),['/order/list'])
})

test('paid feature routes are hidden until the platform reports them enabled',()=>{
  const routes=[screen('/dashboard','仪表盘'),screen('/system/wechat-notices','公众号通知记录',['admin'],{permission:'system.manage',feature:'official_wechat_notifications'}),screen('/user/identity-review','实名认证审核',['admin'],{permission:'user.manage',feature:'identity_verification'})]
  assert.deepEqual(paths(buildNavigation(routes,'admin',()=>true,()=>false)),['/dashboard'])
  assert.deepEqual(paths(buildNavigation(routes,'admin',()=>true,()=>true)),['/dashboard','/user/identity-review','/system/wechat-notices'])
})

test('paid reports and platform configuration respect both staff grants and feature authorization', () => {
  const routes = [screen('/platform/features', '功能商城', ['admin']), screen('/platform/updates', '系统更新', ['admin']),
    screen('/platform/income-analysis', '收益对比分析', ['admin'], { permission: 'finance.read', feature: 'advanced_income_reports' })]
  assert.deepEqual(paths(buildNavigation(routes, 'admin', () => false, () => true)), [])
  assert.deepEqual(paths(buildNavigation(routes, 'admin', code => code === 'finance.read', () => false)), [])
  assert.deepEqual(paths(buildNavigation(routes, 'admin', code => code === 'finance.read', () => true)), ['/platform/income-analysis'])
  assert.deepEqual(paths(buildNavigation(routes, 'cs', () => true, () => true)), [])
})
