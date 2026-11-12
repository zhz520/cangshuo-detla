import { createRouter, createWebHistory } from 'vue-router'
import { getToken, getUserInfo, removeToken } from '@/utils/auth'
import {loadStaffPermissions,can,permissionFor} from '@/utils/staff-permissions'
import { beginNavigation, finishNavigation, failNavigation } from '@/utils/loading-feedback'
import { buildH5Routes, canonicalAdminPath, h5RedirectTarget, isH5Path } from '@/utils/h5-route'

const Layout = () => import('@/layout/index.vue')
const H5Layout = () => import('@/layout/H5Layout.vue')

const desktopRoutes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '仪表盘', icon: 'Odometer', roles: ['admin', 'cs'] }
      },
      {
        path: 'wechat-binding',
        name: 'WechatBinding',
        component: () => import('@/views/wechat/Binding.vue'),
        meta: { title: '公众号消息通知', icon: 'ChatDotRound', roles: ['admin', 'cs'], hidden: true }
      },
      {
        path: '403',
        name: 'Forbidden',
        component: () => import('@/views/error/403.vue'),
        meta: { title: '无权限', errorPage: true, hidden: true }
      },
      {
        path: ':pathMatch(.*)*',
        name: 'NotFound',
        component: () => import('@/views/error/404.vue'),
        meta: { title: '页面不存在', errorPage: true, hidden: true }
      }
    ]
  },
  // 商品管理（仅管理员）
  {
    path: '/product',
    component: Layout,
    redirect: '/product/list',
    meta: { title: '商品管理', icon: 'Goods', roles: ['admin'] },
    children: [
      {
        path: 'list',
        name: 'ProductList',
        component: () => import('@/views/product/ProductList.vue'),
        meta: { title: '商品列表', roles: ['admin'] }
      },
      {
        path: 'category',
        name: 'CategoryList',
        component: () => import('@/views/product/CategoryList.vue'),
        meta: { title: '分类管理', roles: ['admin'] }
      },
      {
        path: 'recommend-category',
        name: 'RecommendCategoryList',
        component: () => import('@/views/product/RecommendCategoryList.vue'),
        meta: { title: '热门分类管理', roles: ['admin'] }
      },
      {
        path: 'form-fields',
        name: 'CategoryFieldList',
        component: () => import('@/views/product/CategoryFieldList.vue'),
        meta: { title: '下单字段配置', roles: ['admin'] }
      },
      {
        path: 'create',
        name: 'ProductCreate',
        component: () => import('@/views/product/ProductForm.vue'),
        meta: { title: '新增商品', roles: ['admin'], hidden: true, activeMenu: '/product/list' }
      },
      {
        path: 'edit/:id',
        name: 'ProductEdit',
        component: () => import('@/views/product/ProductForm.vue'),
        meta: { title: '编辑商品', roles: ['admin'], hidden: true, activeMenu: '/product/list' }
      }
    ]
  },
  // 订单管理
  {
    path: '/order',
    component: Layout,
    redirect: '/order/list',
    meta: { title: '订单管理', icon: 'List', roles: ['admin', 'cs'] },
    children: [
      {
        path: 'list',
        name: 'OrderList',
        component: () => import('@/views/order/OrderList.vue'),
        meta: { title: '订单列表', roles: ['admin', 'cs'] }
      }
    ]
  },
  // 用户管理
  {
    path: '/user',
    component: Layout,
    redirect: '/user/list',
    meta: { title: '用户管理', icon: 'User', roles: ['admin', 'cs'] },
    children: [
      {
        path: 'list',
        name: 'UserList',
        component: () => import('@/views/user/UserList.vue'),
        meta: { title: '用户列表', roles: ['admin', 'cs'] }
      },
      {
        path: 'spending-rank',
        name: 'SpendingRank',
        component: () => import('@/views/user/SpendingRank.vue'),
        meta: { title: '消费榜单', roles: ['admin', 'cs'] }
      },
      {
        path: 'identity-review',
        name: 'IdentityReview',
        component: () => import('@/views/user/IdentityReview.vue'),
        meta: { title: '实名认证审核', roles: ['admin'], permission: 'user.manage', feature: 'identity_verification' }
      }
    ]
  },
  // 打手管理
  {
    path: '/player',
    component: Layout,
    children: [
      {
        path: '',
        name: 'PlayerList',
        component: () => import('@/views/player/PlayerList.vue'),
        meta: { title: '打手管理', icon: 'Avatar', roles: ['admin', 'cs'] }
      }
    ]
  },
  {path:'/player-level',component:Layout,children:[{path:'',name:'PlayerLevels',component:()=>import('@/views/player/PlayerLevels.vue'),meta:{title:'打手等级分类',icon:'Medal',roles:['admin']}}]},
  {path:'/rewards',component:Layout,children:[{path:'',name:'RewardList',component:()=>import('@/views/player/RewardList.vue'),meta:{title:'打赏记录',icon:'Wallet',roles:['admin']}}]},
  {path:'/invitations',component:Layout,children:[{path:'',component:()=>import('@/views/player/Governance.vue'),meta:{title:'入驻邀请',icon:'Link',roles:['admin','cs'],governance:'invitations',permission:'player.manage'}}]},
  {path:'/material-review',component:Layout,children:[{path:'',component:()=>import('@/views/player/Governance.vue'),meta:{title:'主页素材审核',icon:'Picture',roles:['admin','cs'],governance:'revisions',permission:'media.review'}}]},
  {path:'/staff-shifts',component:Layout,children:[{path:'',component:()=>import('@/views/player/Governance.vue'),meta:{title:'上下班管理',icon:'Clock',roles:['admin','cs'],governance:'shifts'}}]},
  {path:'/privacy-requests',component:Layout,children:[{path:'',component:()=>import('@/views/player/Governance.vue'),meta:{title:'隐私与注销申请',icon:'Lock',roles:['admin'],governance:'privacy',permission:'user.manage'}}]},
  {path:'/settlements',component:Layout,children:[{path:'',component:()=>import('@/views/statistics/SettlementLedger.vue'),meta:{title:'自动分账台账',icon:'Tickets',roles:['admin'],permission:'finance.read'}}]},
  // 数据统计（仅管理员）
  {
    path: '/marketing',
    component: Layout,
    redirect: '/marketing/coupons',
    meta: { title: '营销中心', icon: 'Present', roles: ['admin'] },
    children: [
      { path: 'coupons', name: 'MarketingCoupons', component: () => import('@/views/marketing/Coupons.vue'), meta: { title: '优惠券管理', roles: ['admin'], permission: 'system.manage' } },
      { path: 'invites', name: 'MarketingInvites', component: () => import('@/views/marketing/Invites.vue'), meta: { title: '邀请奖励', roles: ['admin'], permission: 'system.manage' } }
    ]
  },
  {
    path: '/statistics',
    component: Layout,
    redirect: '/statistics/income-daily',
    meta: { title: '数据统计', icon: 'DataAnalysis', roles: ['admin'] },
    children: [
      {
        path: 'income-daily',
        name: 'IncomeDaily',
        component: () => import('@/views/statistics/IncomeDaily.vue'),
        meta: { title: '收益日报', roles: ['admin'] }
      }
    ]
  },
  // 投诉处理
  {
    path: '/complaint',
    component: Layout,
    children: [
      {
        path: '',
        name: 'ComplaintList',
        component: () => import('@/views/complaint/ComplaintList.vue'),
        meta: { title: '投诉处理', icon: 'Warning', roles: ['admin', 'cs'] }
      }
    ]
  },
  {
    path: '/refund',
    component: Layout,
    children: [
      {
        path: '',
        name: 'RefundList',
        component: () => import('@/views/refund/RefundList.vue'),
        meta: { title: '退款审批', icon: 'Money', roles: ['admin', 'cs'] }
      }
    ]
  },
  // 换人申请
  {
    path: '/replace',
    component: Layout,
    children: [
      {
        path: '',
        name: 'ReplaceList',
        component: () => import('@/views/replace/ReplaceList.vue'),
        meta: { title: '换人申请', icon: 'Switch', roles: ['admin', 'cs'], hidden: true }
      }
    ]
  },
  // 接力申请（打手申请中途换人由客服指定新打手接手）
  {
    path: '/relay',
    component: Layout,
    children: [
      {
        path: '',
        name: 'RelayList',
        component: () => import('@/views/relay/RelayList.vue'),
        meta: { title: '接力审核', icon: 'ArrowRight', roles: ['admin', 'cs'], hidden: true }
      }
    ]
  },
  {
    path: '/fulfillment-review',
    component: Layout,
    children: [
      {
        path: '',
        name: 'FulfillmentReview',
        component: () => import('@/views/review/FulfillmentReview.vue'),
        meta: { title: '履约审核', icon: 'CircleCheck', roles: ['admin', 'cs'] }
      }
    ]
  },
  // 提现管理（仅管理员）
  {
    path: '/withdraw',
    component: Layout,
    children: [
      {
        path: '',
        name: 'WithdrawList',
        component: () => import('@/views/withdraw/WithdrawList.vue'),
        meta: { title: '提现管理', icon: 'Wallet', roles: ['admin'] }
      }
    ]
  },
  // 客服聊天
  {
    path: '/chat',
    component: Layout,
    redirect: '/chat/service',
    meta: { title: '客服聊天', icon: 'ChatDotRound', roles: ['admin', 'cs'] },
    children: [
      {
        path: 'service',
        name: 'ChatService',
        component: () => import('@/views/chat/ChatService.vue'),
        meta: { title: '在线回复', roles: ['admin', 'cs'] }
      },
      {
        path: 'quick-reply',
        name: 'ChatQuickReply',
        component: () => import('@/views/system/QuickReplyList.vue'),
        meta: { title: '快捷回复', roles: ['admin', 'cs'] }
      },
      {
        path: 'record',
        name: 'ChatRecordList',
        component: () => import('@/views/chat/ChatRecordList.vue'),
        meta: { title: '聊天记录', roles: ['admin', 'cs'] }
      },
      {
        path: 'record/:id',
        name: 'ChatRecordDetail',
        component: () => import('@/views/chat/ChatRecordDetail.vue'),
        meta: { title: '聊天记录详情', roles: ['admin', 'cs'], hidden: true, activeMenu: '/chat/record' }
      }
    ]
  },
  {
    path: '/platform',
    component: Layout,
    redirect: '/platform/features',
    meta: { title: '功能与更新', icon: 'Box', roles: ['admin'] },
    children: [
      { path: 'features', name: 'FeatureMarket', component: () => import('@/views/platform/FeatureMarket.vue'), meta: { title: '功能商城', roles: ['admin'] } },
      { path: 'themes', name: 'ThemeStore', component: () => import('@/views/platform/ThemeStore.vue'), meta: { title: '主题商店', roles: ['admin'], permission: 'system.manage' } },
      { path: 'updates', name: 'SystemUpdates', component: () => import('@/views/platform/SystemUpdates.vue'), meta: { title: '系统更新', roles: ['admin'] } },
      { path: 'income-analysis', name: 'IncomeAnalysis', component: () => import('@/views/platform/IncomeAnalysis.vue'), meta: { title: '收益对比分析', roles: ['admin'], permission: 'finance.read', feature: 'advanced_income_reports' } }
    ]
  },
  // 系统管理 (仅 admin)
  {
    path: '/system',
    component: Layout,
    redirect: '/system/admin',
    meta: { title: '系统管理', icon: 'Setting', roles: ['admin'] },
    children: [
      {path:'wechat-notices',component:()=>import('@/views/system/WechatNotices.vue'),meta:{title:'通知记录',roles:['admin'],permission:'system.manage'}},
      {path:'email-notifications',name:'EmailNotifications',component:()=>import('@/views/system/EmailNotifications.vue'),meta:{title:'邮件通知',roles:['admin'],permission:'system.manage',feature:'email_notifications'}},
      {path:'roles',component:()=>import('@/views/system/StaffRoles.vue'),meta:{title:'角色与权限',roles:['admin'],permission:'system.manage'}},
      {
        path: 'admin',
        name: 'AdminManage',
        component: () => import('@/views/system/AdminList.vue'),
        meta: { title: '管理员管理', roles: ['admin'] }
      },
      {
        path: 'notice',
        name: 'NoticeManage',
        component: () => import('@/views/system/NoticeList.vue'),
        meta: { title: '公告管理', roles: ['admin'] }
      },
      {
        path: 'banner',
        name: 'BannerManage',
        component: () => import('@/views/system/BannerList.vue'),
        meta: { title: '轮播图管理', roles: ['admin'] }
      },
      {path:'reward-grab',name:'RewardGrabSettings',component:()=>import('@/views/system/RewardGrabSettings.vue'),meta:{title:'打赏与抢单设置',roles:['admin']}},
      {path:'player-layout',name:'PlayerLayoutSettings',component:()=>import('@/views/system/PlayerLayoutSettings.vue'),meta:{title:'打手列表布局',roles:['admin'],permission:'system.manage',feature:'player_directory_layouts'}},
      {
        path: 'config',
        name: 'SysConfig',
        component: () => import('@/views/system/SysConfig.vue'),
        meta: { title: '系统配置', roles: ['admin'] }
      },
      {
        path: 'recharge-packages',
        name: 'RechargePackages',
        component: () => import('@/views/system/RechargePackage.vue'),
        meta: { title: '余额充值套餐', roles: ['admin'] }
      },
      {
        path: 'operation-log',
        name: 'OperationLog',
        component: () => import('@/views/system/OperationLog.vue'),
        meta: { title: '操作日志', roles: ['admin'] }
      }
    ]
  },
]

const pageRoutes = desktopRoutes.filter(route => route.path !== '/403' && route.path !== '/:pathMatch(.*)*')
const h5Routes = buildH5Routes(pageRoutes, H5Layout)
const h5Root = h5Routes.find(route => route.path === '/h5')
h5Root?.children.push(
  {
    path: '403',
    name: 'H5Forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { title: '无权限', mobileAlias: true, h5Alias: true, hidden: true }
  },
  {
    path: ':pathMatch(.*)*',
    name: 'H5NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', mobileAlias: true, h5Alias: true, hidden: true }
  }
)
const routes = [
  ...pageRoutes,
  {
    path: '/h5/login',
    name: 'H5Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', h5: true }
  },
  ...h5Routes,
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
const whiteList = ['/login', '/h5/login']
router.beforeEach(async (to, from, next) => {
  beginNavigation(to)
  const token = getToken()
  const h5 = isH5Path(to.path)

  // Keep canonical links inside the H5 shell. Explicit PC entry uses a full page load.
  const h5Redirect = h5RedirectTarget(to, from)
  if (h5Redirect) {
    next(h5Redirect)
    return
  }

  if (token) {
    if (to.path === '/login' || to.path === '/h5/login') {
      next(h5 ? '/h5/dashboard' : '/dashboard')
    } else {
      const user = getUserInfo()
      try { await loadStaffPermissions() } catch (error) {
        if (error.code === 401) { removeToken(); next(h5 ? '/h5/login' : '/login'); return }
        throw error
      }
      const permission=to.meta.permission||permissionFor(canonicalAdminPath(to.path))
      if(permission&&!can(permission)){next(h5 ? '/h5/403' : '/403');return}
      if (to.matched.some(record => record.meta.roles && !record.meta.roles.includes(user?.role))) { next(h5 ? '/h5/403' : '/403'); return }
      if (to.meta.feature) {
        const { loadPlatformFeatureVisibility, platformFeatureAllowed, platformFeatureError } = await import('@/utils/platform-feature-visibility')
        await loadPlatformFeatureVisibility()
        if (platformFeatureError.value) throw new Error(platformFeatureError.value)
        if (!platformFeatureAllowed(to.meta.feature)) { next(h5 ? '/h5/403' : '/403'); return }
      }
      const roles = to.meta.roles
      if (roles && !roles.includes(user?.role)) {
        next(h5 ? '/h5/403' : '/403')
      } else {
        next()
      }
    }
  } else {
    if (whiteList.includes(to.path)) {
      next()
    } else {
      next({
        path: h5 ? '/h5/login' : '/login',
        query: { redirect: to.fullPath }
      })
    }
  }
})

router.afterEach(to => finishNavigation(to))
router.onError(failNavigation)

export default router
