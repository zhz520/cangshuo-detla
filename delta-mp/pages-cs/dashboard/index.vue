<template>
  <view class="cs-dashboard tab-page">
    <scroll-view scroll-y class="dashboard-scroll tab-page-scroll" :show-scrollbar="false">
      <view class="workbench-shell">
        <view class="workbench-hero">
          <view class="hero-orbit hero-orbit-one" />
          <view class="hero-orbit hero-orbit-two" />
          <view class="hero-top">
            <view class="hero-copy">
              <text class="hero-eyebrow">{{ roleLabel }} · {{ greeting }}</text>
              <text class="hero-title">{{ displayName }}</text>
              <text class="hero-subtitle">今天也把每一单稳稳接住</text>
            </view>
            <button
              class="refresh-button"
              :class="{ spinning: refreshing }"
              :disabled="refreshing"
              aria-label="刷新工作台"
              @click="refreshAll(true)"
            >↻</button>
          </view>

          <view v-if="!isAdmin" class="shift-panel" :class="{ online: shift.online }">
            <view class="shift-copy">
              <view class="shift-title-row">
                <text class="shift-dot" />
                <text class="shift-title">{{ shiftTitle }}</text>
                <text v-if="shiftLoading" class="shift-loading">同步中</text>
              </view>
              <text class="shift-hint">{{ shiftHint }}</text>
              <text v-if="shiftError" class="shift-error" @click="loadShift(true)">{{ shiftError }} · 重试</text>
            </view>
            <view
              class="shift-control"
              :class="{ active: shift.online, disabled: !shiftReady || shiftLoading }"
              role="button"
              :aria-disabled="!shiftReady || shiftLoading"
              @click="toggleShift"
            >
              <view class="shift-knob" />
              <text>{{ shift.online ? '上班中' : '已下班' }}</text>
            </view>
          </view>

          <view v-else class="shift-panel admin" @click="shiftError && loadShift(true)">
            <view class="shift-copy">
              <view class="shift-title-row">
                <text class="shift-dot" />
                <text class="shift-title">客服在线情况</text>
              </view>
              <text class="shift-hint">{{ adminShiftHint }}</text>
            </view>
            <view class="shift-readonly">
              <text class="shift-readonly-value">{{ formatMetric(onDutyCount) }}</text>
              <text class="shift-readonly-label">当班客服</text>
            </view>
          </view>

          <view class="pending-heading">
            <text class="pending-title">今日待办</text>
            <text class="pending-tip">点击数字直接处理</text>
          </view>
          <view v-if="!dashboardReady" class="pending-grid pending-skeleton" aria-label="待办加载中">
            <view v-for="n in 5" :key="n" class="pending-skeleton-item">
              <view class="skeleton-block count" />
              <view class="skeleton-block label" />
            </view>
          </view>
          <view v-else class="pending-grid">
            <view
              v-for="task in pendingTasks"
              :key="task.key"
              class="pending-item"
              :class="{ alert: task.alert }"
              role="button"
              :aria-label="task.label + ' ' + formatCount(task.value)"
              @click="openPath(task.route)"
            >
              <image class="pending-icon" :src="task.icon" mode="aspectFit" />
              <view class="pending-value-row">
              <text class="pending-value">{{ formatMetric(task.value) }}</text>
                <text v-if="task.alert" class="pending-alert-dot" />
              </view>
              <text class="pending-label">{{ task.label }}</text>
            </view>
          </view>
        </view>

        <view v-if="dashboardError" class="inline-error" role="alert" @click="loadDashboard(true)">
          <view class="error-mark">!</view>
          <view class="error-copy">
            <text class="error-title">{{ dashboardError }}</text>
            <text class="error-hint">当前展示最近一次数据，点击重试</text>
          </view>
          <text class="error-action">重试</text>
        </view>

        <view v-if="!dashboardReady" class="panel skeleton-panel" aria-label="工作台数据加载中">
          <view class="skeleton-line title" />
          <view class="skeleton-grid">
            <view v-for="n in 3" :key="n" class="skeleton-stat">
              <view class="skeleton-block value" />
              <view class="skeleton-block label" />
            </view>
          </view>
          <view class="skeleton-line wide" />
        </view>

        <template v-else>
          <view class="panel today-panel">
            <view class="panel-heading">
              <view>
                <text class="panel-title">今日数据</text>
                <text class="panel-caption">{{ updatedLabel }}</text>
              </view>
              <text class="panel-link" @click="openPath('/pages/workbench/index')">查看待办 ›</text>
            </view>

            <view class="metric-grid">
              <view
                v-for="item in metrics"
                :key="item.key"
                class="metric-item"
                :class="{ clickable: item.route }"
                @click="openPath(item.route)"
              >
                <text class="metric-value" :class="{ amount: item.money }">{{ item.value }}</text>
                <text class="metric-label">{{ item.label }}</text>
                <text v-if="item.subLabel" class="metric-sub">{{ item.subLabel }}</text>
              </view>
            </view>

            <view class="player-presence">
              <view class="presence-main">
                <text class="presence-title">打手在线</text>
                <text class="presence-caption">实时掌握可接单人力</text>
              </view>
              <view class="presence-counts">
                <view class="presence-count online" role="button" @click="openPath('/pages-cs/player/list?presence=ONLINE')">
                  <text class="presence-value">{{ formatMetric(playerCounts.online) }}</text>
                  <text class="presence-label">在线</text>
                </view>
                <view class="presence-divider" />
                <view class="presence-count" role="button" @click="openPath('/pages-cs/player/list?presence=OFFLINE')">
                  <text class="presence-value">{{ formatMetric(playerCounts.offline) }}</text>
                  <text class="presence-label">离线</text>
                </view>
              </view>
            </view>
          </view>

          <view v-if="productActions.length" class="panel product-panel">
            <view class="panel-heading compact">
              <view>
                <text class="panel-title">我的商品 <text class="title-count">({{ formatMetric(productCount) }})</text></text>
                <text class="panel-caption">商品与分类快捷入口</text>
              </view>
              <text class="panel-link" @click="openPath('/pages-cs/product/list')">查看商品 ›</text>
            </view>
            <view class="mobile-action-grid three">
              <view
                v-for="action in productActions"
                :key="action.key"
                class="mobile-action"
                :class="{ accent: action.accent }"
                role="button"
                @click="openQuickAction(action)"
              >
                <view class="mobile-action-icon"><image :src="action.icon" mode="aspectFit" /></view>
                <text>{{ action.label }}</text>
              </view>
            </view>
          </view>

          <view v-for="group in actionGroups" :key="group.key" class="panel action-panel">
            <view class="panel-heading compact">
              <view>
                <text class="panel-title">{{ group.title }}</text>
                <text class="panel-caption">{{ group.caption }}</text>
              </view>
            </view>
            <view class="mobile-action-grid" :class="group.columns === 2 ? 'two' : 'three'">
              <view
                v-for="action in group.items"
                :key="action.key"
                class="mobile-action"
                role="button"
                :aria-label="action.label"
                @click="openQuickAction(action)"
              >
                <view class="mobile-action-icon">
                  <image :src="action.icon" mode="aspectFit" />
                  <text v-if="action.badge > 0" class="quick-badge">{{ formatCount(action.badge) }}</text>
                </view>
                <text>{{ action.label }}</text>
                <text v-if="action.hint" class="mobile-action-hint">{{ action.hint }}</text>
              </view>
            </view>
          </view>

          <view v-if="reviewItems.length" class="panel review-panel">
            <view class="panel-heading">
              <view>
                <text class="panel-title">审核中心</text>
                <text class="panel-caption">入驻、素材与履约统一入口</text>
              </view>
              <text class="panel-link" @click="openPath('/pages/workbench/index')">全部待办 ›</text>
            </view>
            <view class="review-list">
              <view
                v-for="item in reviewItems"
                :key="item.key"
                class="review-row"
                role="button"
                @click="openPath(item.route)"
              >
                <view class="review-mark" :class="item.tone">{{ item.short }}</view>
                <view class="review-copy">
                  <text class="review-title">{{ item.label }}</text>
                  <text class="review-caption">{{ item.caption }}</text>
                </view>
                <view class="review-side">
                  <text class="review-count">{{ formatMetric(item.value) }}</text>
                  <text class="review-arrow">›</text>
                </view>
              </view>
            </view>
          </view>
        </template>

        <view class="tab-page-bottom-spacer" />
      </view>
    </scroll-view>
    <CustomTabBar :current="0" />
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import CustomTabBar from '@/components/CustomTabBar.vue'
import { getCsPermissions, getCsShiftStatus, getDashboard, toggleCsShift } from '@/pages-cs/api/cs'
import { getCsInfo } from '@/utils/auth'
import { useRemindStore } from '@/store/remind'

const PERMISSION_FALLBACK = ['order.read', 'order.manage', 'player.manage', 'media.review', 'chat.manage', 'user.read']
const CACHE_TTL = 15000
const remindStore = useRemindStore()
const csInfo = getCsInfo() || {}

const dashboard = ref({})
const dashboardReady = ref(false)
const dashboardError = ref('')
const permissions = ref([])
const permissionsReady = ref(false)
const shift = ref({ online: false })
const shiftReady = ref(false)
const shiftLoading = ref(false)
const shiftError = ref('')
const loading = ref(false)
const refreshing = ref(false)
const updatedAt = ref('')

let dashboardCache = null
let permissionsCache = null
let shiftCache = null
let activeLoad = null

const isAdmin = computed(() => ['ADMIN', 'BOTH'].includes(String(csInfo.role || '').toUpperCase()))
const displayName = computed(() => csInfo.nickname || (isAdmin.value ? '平台管理员' : '客服'))
const roleLabel = computed(() => isAdmin.value ? 'ADMIN 管理员' : 'CS 客服')
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})
const updatedLabel = computed(() => updatedAt.value ? updatedAt.value + ' 更新' : '下拉可刷新')

function firstNumber(...values) {
  for (const value of values) {
    if (value === null || value === undefined || value === '') continue
    const number = Number(value)
    if (Number.isFinite(number)) return number
  }
  return null
}

function sumKnown(values) {
  const numbers = values.filter(value => value !== null && value !== undefined)
  return numbers.length ? numbers.reduce((sum, value) => sum + Number(value || 0), 0) : null
}

function countByKind(kind) {
  return firstNumber(remindStore.count(kind, 'pending'))
}

const pendingTasks = computed(() => {
  const data = dashboard.value
  const auditFallback = sumKnown([
    countByKind('ENROLLMENT'),
    countByKind('MATERIAL'),
    countByKind('IDENTITY'),
    countByKind('PRIVACY')
  ])
  const refundValue = firstNumber(data.pendingRefunds, data.refundApprovals, data.pendingRefundApprovals)
  const tasks = [
    {
      key: 'assign',
      label: '待指派',
      icon: '/static/icons/订单.svg',
      value: firstNumber(data.pendingAssign),
      route: '/pages-cs/order/list?status=PAID',
      allowed: can('order.manage')
    },
    {
      key: 'service',
      label: '服务中',
      icon: '/static/icons/提示.svg',
      value: firstNumber(data.inProgress, data.inService, data.todayInProgress),
      route: '/pages-cs/order/list?status=IN_PROGRESS',
      allowed: can('order.read')
    },
    {
      key: 'audit',
      label: '待审核',
      icon: '/static/icons/安全认证.svg',
      value: firstNumber(data.pendingAudits, data.auditPending) ?? auditFallback,
      route: '/pages/workbench/index',
      allowed: canAny(['player.manage', 'media.review', 'user.manage'])
    },
    {
      key: 'refund',
      label: '退款审批',
      icon: '/static/icons/钞票.svg',
      value: refundValue ?? countByKind('REFUND'),
      route: '/pages-cs/refund/list',
      allowed: can('order.refund')
    },
    {
      key: 'complaint',
      label: '投诉',
      icon: '/static/icons/警示.svg',
      value: firstNumber(data.pendingComplaints) ?? remindStore.complaintUnread,
      route: '/pages-cs/complaint/list',
      allowed: can('order.manage')
    }
  ]
  return tasks
    .filter(task => task.allowed)
    .map(task => ({ ...task, alert: Number(task.value || 0) > 0 }))
})

const metrics = computed(() => {
  const data = dashboard.value
  return [
    {
      key: 'visitors',
      label: '访客数',
      value: formatMetric(firstNumber(data.todayVisitors, data.visitors, data.visitorCount)),
      subLabel: '登录日活 ' + formatMetric(firstNumber(data.todayActiveUsers, data.activeUsers, data.dailyActiveUsers)),
      route: ''
    },
    {
      key: 'orders',
      label: '有效订单',
      value: formatMetric(firstNumber(data.validOrders, data.todayValidOrders, data.todayOrders)),
      route: '/pages-cs/order/list'
    },
    {
      key: 'collected',
      label: '已收款',
      value: formatAmount(firstNumber(data.collectedAmount, data.todayCollectedAmount, data.todayAmount)),
      route: '',
      money: true
    }
  ]
})

const playerCounts = computed(() => {
  const data = dashboard.value
  const online = firstNumber(data.onlinePlayers, data.activePlayers, data.onlinePlayerCount)
  const total = firstNumber(data.totalPlayers, data.playerTotal)
  const offline = firstNumber(data.offlinePlayers, data.offlinePlayerCount)
    ?? (online !== null && total !== null ? Math.max(0, total - online) : null)
  return { online, offline }
})

const onDutyCount = computed(() => firstNumber(
  shift.value.onlineCsCount,
  shift.value.onDutyCs,
  shift.value.onDutyCount,
  shift.value.staffOnlineCount,
  shift.value.count
))

const shiftTitle = computed(() => {
  if (!shiftReady.value) return '上班状态连接中'
  return shift.value.online ? '当前正在当班' : '当前已下班'
})
const shiftHint = computed(() => {
  if (shiftError.value) return '未能同步客服排班状态'
  if (shift.value.online) return '当班期间接收订单、审核、投诉与退款待办'
  return '下班后不再接收需要人工处理的业务提醒'
})
const adminShiftHint = computed(() => {
  if (shiftError.value) return '客服排班暂未同步 · 点击重试'
  return '管理员不参与客服排班，账号始终保持接收权限'
})

const productCount = computed(() => firstNumber(
  dashboard.value.productCount,
  dashboard.value.productTotal,
  dashboard.value.totalProducts
) ?? 0)

const productActions = computed(() => {
  if (!can('product.manage')) return []
  return [
    { key:'product-list', label:'商品管理', icon:'/static/icons/购物.svg', route:'/pages-cs/product/list' },
    { key:'category-list', label:'分类管理', icon:'/static/icons/分类.svg', route:'/pages-cs/category/list' },
    { key:'product-create', label:'添加商品', icon:'/static/icons/购物.svg', route:'/pages-cs/product/edit', accent:true }
  ]
})

const actionGroups = computed(() => {
  const data = dashboard.value
  const groups = [
    {
      key:'business', title:'业务办理', caption:'订单、售后与资金', columns:3,
      items:[
        { key:'orders', label:'订单管理', icon:'/static/icons/订单.svg', route:'/pages-cs/order/list', allowed:can('order.read') },
        { key:'refunds', label:'退款审批', icon:'/static/icons/钞票.svg', route:'/pages-cs/refund/list', allowed:can('order.refund'), badge:firstNumber(data.pendingRefunds,data.refundApprovals) },
        { key:'complaints', label:'投诉售后', icon:'/static/icons/警示.svg', route:'/pages-cs/complaint/list', allowed:can('order.manage') },
        { key:'withdraws', label:'提现审核', icon:'/static/icons/理财.svg', route:'/pages-cs/withdraw/list', allowed:can('withdraw.review') },
      ].filter(item=>item.allowed)
    },
    {
      key:'people', title:'人员与客户', caption:'用户、接单员与运营账号', columns:2,
      items:[
        { key:'players', label:'打手管理', icon:'/static/icons/会员.svg', route:'/pages-cs/player/list', allowed:can('player.manage') },
        { key:'customers', label:'客户管理', icon:'/pages-cs/static/icons/会员.svg', route:'/pages-cs/user/list', allowed:canAny(['user.read','user.manage']) },
        { key:'staff', label:'员工与角色', icon:'/static/icons/安全认证.svg', allowed:can('system.manage'), hint:'电脑后台' },
        { key:'announcements', label:'公告管理', icon:'/static/icons/公告.svg', allowed:can('system.manage'), hint:'电脑后台' }
      ].filter(item=>item.allowed)
    },
    {
      key:'support', title:'消息与服务', caption:'即时沟通与账号服务', columns:2,
      items:[
        { key:'inquiries', label:'客户咨询', icon:'/static/icons/客服.svg', route:'/pages-cs/chat/list', allowed:can('chat.manage') },
        { key:'messages', label:'系统通知', icon:'/static/icons/提示.svg', route:'/pages/message/index', badge:remindStore.systemUnread },
        { key:'quick-reply', label:'快捷话术', icon:'/static/icons/快捷.svg', route:'/pages-cs/quick-reply/index', allowed:can('chat.manage') },
        { key:'profile', label:'编辑资料', icon:'/static/icons/编辑.svg', route:'/pages-cs/profile/edit' },
        { key:'updates', label:'版本更新', icon:'/static/icons/短信.svg', route:'/pages/workbench/index?kind=UPDATE', allowed:can('system.manage') }
      ].filter(item=>item.allowed !== false)
    }
  ]
  return groups.filter(group=>group.items.length)
})

const reviewItems = computed(() => {
  const data = dashboard.value
  const fulfillmentFallback = sumKnown([countByKind('RELAY'), countByKind('REPLACE')])
  const items = [
    {
      key: 'enrollment',
      short: '入',
      tone: 'dark',
      label: '打手入驻审核',
      caption: '资料审核与准入处理',
      value: firstNumber(data.pendingEnrollments, data.pendingPlayerAudits) ?? countByKind('ENROLLMENT'),
      route: '/pages/workbench/index?kind=ENROLLMENT',
      allowed: can('player.manage')
    },
    {
      key: 'material',
      short: '审',
      tone: 'soft',
      label: '主页素材审核',
      caption: '照片、语音与主页内容',
      value: firstNumber(data.pendingMaterials, data.pendingMaterialReviews) ?? countByKind('MATERIAL'),
      route: '/pages/workbench/index?kind=MATERIAL',
      allowed: can('media.review')
    },
    {
      key: 'fulfillment',
      short: '履',
      tone: 'line',
      label: '履约审核',
      caption: '接力与换人申请统一处理',
      value: firstNumber(data.pendingFulfillment, data.pendingRelayReplace) ?? fulfillmentFallback,
      route: '/pages-cs/fulfillment/list',
      allowed: can('order.manage')
    }
  ]
  return items.filter(item => item.allowed)
})

function hasPermission(code) {
  if (isAdmin.value) return true
  if (!permissionsReady.value) return false
  const granted = permissions.value || []
  return granted.includes('*') || granted.includes(code)
}

function canAny(codes) {
  return codes.some(code => hasPermission(code))
}

function can(code) {
  return hasPermission(code)
}

function formatCount(value) {
  if (value === null || value === undefined || value === '') return '—'
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  return number > 99 ? '99+' : String(Math.max(0, Math.trunc(number)))
}

function formatMetric(value) {
  if (value === null || value === undefined || value === '') return '—'
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  return number.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

function formatAmount(value) {
  if (value === null || value === undefined || value === '') return '¥—'
  return '¥' + Number(value || 0).toFixed(2)
}

function fresh(entry) {
  return entry && Date.now() - entry.at < CACHE_TTL
}

async function loadDashboard(force = false) {
  if (!force && fresh(dashboardCache)) {
    dashboard.value = dashboardCache.data
    dashboardReady.value = true
    updatedAt.value = dashboardCache.label
    return
  }
  dashboardError.value = ''
  try {
    const response = await getDashboard()
    dashboard.value = response.data || {}
    dashboardReady.value = true
    const label = clockLabel()
    updatedAt.value = label
    dashboardCache = { at: Date.now(), data: dashboard.value, label }
  } catch (error) {
    if (dashboardCache) {
      dashboard.value = dashboardCache.data
      dashboardReady.value = true
      updatedAt.value = dashboardCache.label
    }
    dashboardError.value = '今日数据暂未更新'
    throw error
  }
}

async function loadPermissions(force = false) {
  if (isAdmin.value) {
    permissions.value = ['*']
    permissionsReady.value = true
    return
  }
  if (!force && fresh(permissionsCache)) {
    permissions.value = permissionsCache.data
    permissionsReady.value = true
    return
  }
  try {
    const response = await getCsPermissions()
    permissions.value = Array.isArray(response.data) ? response.data : []
    permissionsReady.value = true
    permissionsCache = { at: Date.now(), data: permissions.value }
  } catch (_) {
    permissions.value = [...PERMISSION_FALLBACK]
    permissionsReady.value = true
  }
}

function applyShiftState(payload = {}) {
  const rawOnline = firstBoolean(
    payload.online,
    payload.onDuty,
    payload.isOnline,
    payload.currentOnline,
    payload.shift?.online,
    payload.shift?.onDuty
  )
  if (rawOnline !== null) shift.value = { ...shift.value, online: rawOnline }
  shift.value = {
    ...shift.value,
    onlineCsCount: firstNumber(
      payload.onlineCsCount,
      payload.onDutyCs,
      payload.onDutyCount,
      payload.staffOnlineCount,
      payload.onlineStaffCount,
      payload.count,
      payload.shift?.onlineCsCount
    )
  }
  shiftReady.value = true
  shiftError.value = ''
}

function firstBoolean(...values) {
  for (const value of values) {
    if (typeof value === 'boolean') return value
    if (value === 1 || value === '1' || value === 'true') return true
    if (value === 0 || value === '0' || value === 'false') return false
  }
  return null
}

async function loadShift(force = false) {
  if (!force && fresh(shiftCache)) {
    applyShiftState(shiftCache.data)
    return
  }
  shiftError.value = ''
  try {
    const response = await getCsShiftStatus()
    const data = response.data || {}
    applyShiftState(data)
    shiftCache = { at: Date.now(), data }
  } catch (error) {
    shiftError.value = '排班状态暂不可用'
    throw error
  }
}

function clockLabel() {
  const now = new Date()
  return String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0')
}

async function reload(force = false) {
  if (activeLoad) return activeLoad
  loading.value = !dashboardReady.value
  refreshing.value = force
  activeLoad = Promise.allSettled([
    loadDashboard(force),
    loadPermissions(force),
    loadShift(force)
  ]).finally(() => {
    loading.value = false
    refreshing.value = false
    activeLoad = null
  })
  return activeLoad
}

async function refreshAll(showToast = false) {
  await reload(true)
  if (showToast) uni.showToast({ title: dashboardError.value ? '部分数据刷新失败' : '工作台已刷新', icon: 'none' })
}

async function toggleShift() {
  if (isAdmin.value || shiftLoading.value || !shiftReady.value) return
  shiftLoading.value = true
  shiftError.value = ''
  const nextOnline = !shift.value.online
  try {
    const response = await toggleCsShift(nextOnline)
    if (response.data && Object.keys(response.data).length) applyShiftState(response.data)
    else shift.value = { ...shift.value, online: nextOnline }
    uni.showToast({ title: nextOnline ? '已开始上班' : '已下班', icon: 'none' })
    try {
      await loadShift(true)
    } catch (_) {
      shiftError.value = '上班状态已切换，排班人数同步失败'
    }
  } catch (_) {
    shiftError.value = '上班状态切换失败'
  } finally {
    shiftLoading.value = false
  }
}

function openPath(url) {
  if (!url) return
  uni.navigateTo({ url })
}

function openQuickAction(action) {
  if (action.route) {
    openPath(action.route)
    return
  }
  uni.showModal({
    title: action.label + '管理',
    content: '该模块目前需要使用电脑端管理后台操作。',
    showCancel: false
  })
}

onShow(() => {
  uni.setNavigationBarTitle({ title: isAdmin.value ? '运营管理后台' : '客服工作台' })
  reload(false)
  remindStore.fetchCsRemind()
})

onPullDownRefresh(async () => {
  await reload(true)
  uni.stopPullDownRefresh()
})
</script>

<style lang="scss" scoped>
.cs-dashboard {
  background: #f2f3f5;
  color: #17191c;
  overflow-x: hidden;
  position: relative;
}

.dashboard-scroll {
  width: 100%;
  background:
    radial-gradient(circle at 92% 3%, rgba(255, 255, 255, .95) 0, rgba(255, 255, 255, 0) 34%),
    linear-gradient(180deg, #f7f8fa 0, #f2f3f5 540rpx, #f2f3f5 100%);
}

.workbench-shell {
  width: 100%;
  max-width: 860rpx;
  box-sizing: border-box;
  margin: 0 auto;
  padding: 24rpx 24rpx 0;
  overflow-x: hidden;
}

.workbench-hero {
  position: relative;
  overflow: hidden;
  padding: 30rpx 28rpx 24rpx;
  border-radius: 32rpx;
  background:
    radial-gradient(circle at 100% 0, rgba(255, 255, 255, .17), transparent 36%),
    linear-gradient(145deg, #141619 0%, #202328 58%, #101214 100%);
  box-shadow: 0 22rpx 50rpx rgba(20, 22, 25, .2);
  box-sizing: border-box;
}

.hero-orbit {
  position: absolute;
  border: 1rpx solid rgba(255, 255, 255, .08);
  border-radius: 50%;
  pointer-events: none;
}
.hero-orbit-one { width: 360rpx; height: 360rpx; right: -190rpx; top: -180rpx; }
.hero-orbit-two { width: 210rpx; height: 210rpx; right: -88rpx; top: -84rpx; }

.hero-top {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18rpx;
}

.hero-copy { min-width: 0; flex: 1; }
.hero-eyebrow {
  display: block;
  color: rgba(255, 255, 255, .58);
  font-size: 21rpx;
  letter-spacing: 1rpx;
}
.hero-title {
  display: block;
  margin-top: 8rpx;
  color: #fff;
  font-size: 42rpx;
  line-height: 1.22;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hero-subtitle {
  display: block;
  margin-top: 8rpx;
  color: rgba(255, 255, 255, .5);
  font-size: 23rpx;
}

.refresh-button {
  flex: 0 0 auto;
  width: 64rpx;
  height: 64rpx;
  min-height: 64rpx;
  margin: 0;
  padding: 0;
  line-height: 60rpx;
  border: 1rpx solid rgba(255, 255, 255, .15);
  border-radius: 50%;
  background: rgba(255, 255, 255, .08);
  color: #fff;
  font-size: 30rpx;
  font-weight: 500;
}
.refresh-button::after { border: 0; }
.refresh-button.spinning { opacity: .6; transform: rotate(50deg); }

.shift-panel {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 18rpx;
  margin-top: 26rpx;
  padding: 20rpx;
  border: 1rpx solid rgba(255, 255, 255, .11);
  border-radius: 22rpx;
  background: rgba(255, 255, 255, .07);
  box-sizing: border-box;
}
.shift-panel.online { background: rgba(255, 255, 255, .11); }
.shift-panel.admin { background: rgba(255, 255, 255, .055); }
.shift-copy { flex: 1; min-width: 0; }
.shift-title-row { display: flex; align-items: center; gap: 10rpx; }
.shift-dot { width: 13rpx; height: 13rpx; border-radius: 50%; background: #838990; box-shadow: 0 0 0 5rpx rgba(255,255,255,.06); }
.shift-panel.online .shift-dot { background: #9de3b8; box-shadow: 0 0 0 5rpx rgba(157, 227, 184, .12); }
.shift-title { color: #fff; font-size: 26rpx; font-weight: 700; }
.shift-loading { color: rgba(255,255,255,.48); font-size: 20rpx; }
.shift-hint { display: block; margin-top: 7rpx; color: rgba(255,255,255,.48); font-size: 21rpx; line-height: 1.45; }
.shift-error { display: block; margin-top: 6rpx; color: #ffbdc2; font-size: 20rpx; }

.shift-control {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 9rpx;
  min-height: 54rpx;
  padding: 0 16rpx 0 9rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, .1);
  color: rgba(255,255,255,.64);
  font-size: 21rpx;
  transition: opacity .16s ease, background-color .16s ease;
}
.shift-control.active { background: rgba(157, 227, 184, .16); color: #c8f2d7; }
.shift-control.disabled { opacity: .5; pointer-events: none; }
.shift-knob { width: 36rpx; height: 36rpx; border-radius: 50%; background: #747a81; transition: background-color .16s ease; }
.shift-control.active .shift-knob { background: #9de3b8; }
.shift-readonly { flex: 0 0 auto; text-align: right; }
.shift-readonly-value { display: block; color: #fff; font-size: 34rpx; font-weight: 800; line-height: 1; }
.shift-readonly-label { display: block; margin-top: 5rpx; color: rgba(255,255,255,.42); font-size: 19rpx; }

.pending-heading {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12rpx;
  margin: 28rpx 2rpx 14rpx;
}
.pending-title { color: rgba(255,255,255,.92); font-size: 25rpx; font-weight: 700; }
.pending-tip { color: rgba(255,255,255,.36); font-size: 19rpx; }

.pending-grid {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8rpx;
}
.pending-item {
  min-width: 0;
  min-height: 104rpx;
  padding: 15rpx 4rpx 13rpx;
  border-radius: 18rpx;
  background: rgba(255,255,255,.055);
  text-align: center;
  box-sizing: border-box;
  transition: background-color .16s ease, opacity .16s ease;
}
.pending-item:active { opacity: .7; background: rgba(255,255,255,.1); }
.pending-item.alert { background: rgba(255,255,255,.09); }
.pending-value-row { height: 42rpx; display: flex; align-items: center; justify-content: center; gap: 4rpx; }
.pending-value { color: #fff; font-size: 31rpx; line-height: 1; font-weight: 800; font-variant-numeric: tabular-nums; }
.pending-alert-dot { width: 8rpx; height: 8rpx; border-radius: 50%; background: #f0a8ad; }
.pending-label { display: block; margin-top: 7rpx; color: rgba(255,255,255,.48); font-size: 19rpx; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.pending-skeleton-item { min-height: 104rpx; padding: 18rpx 6rpx; box-sizing: border-box; border-radius: 18rpx; background: rgba(255,255,255,.045); }
.pending-skeleton-item .count { width: 42rpx; height: 28rpx; margin: 0 auto 13rpx; }
.pending-skeleton-item .label { width: 56rpx; height: 15rpx; margin: 0 auto; }

.inline-error {
  display: flex;
  align-items: center;
  gap: 14rpx;
  margin-top: 18rpx;
  padding: 17rpx 20rpx;
  border: 1rpx solid #f0d5d8;
  border-radius: 18rpx;
  background: #fff8f8;
}
.error-mark { flex: 0 0 auto; width: 34rpx; height: 34rpx; line-height: 34rpx; border-radius: 50%; text-align: center; background: #17191c; color: #fff; font-size: 21rpx; font-weight: 800; }
.error-copy { flex: 1; min-width: 0; }
.error-title { display: block; font-size: 23rpx; font-weight: 600; color: #7a3037; }
.error-hint { display: block; margin-top: 3rpx; font-size: 19rpx; color: #9a6e72; }
.error-action { flex: 0 0 auto; font-size: 22rpx; font-weight: 700; color: #17191c; }

.panel {
  margin-top: 20rpx;
  padding: 26rpx;
  border: 1rpx solid #e9ebee;
  border-radius: 26rpx;
  background: #fff;
  box-shadow: 0 10rpx 28rpx rgba(23, 25, 28, .035);
  box-sizing: border-box;
}
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14rpx; margin-bottom: 24rpx; }
.panel-heading.compact { margin-bottom: 20rpx; }
.panel-title { display: block; color: #17191c; font-size: 30rpx; line-height: 1.2; font-weight: 800; }
.panel-caption { display: block; margin-top: 6rpx; color: #92979e; font-size: 20rpx; }
.panel-link { flex: 0 0 auto; color: #5f646b; font-size: 21rpx; padding-top: 4rpx; }

.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10rpx; }
.metric-item { min-width: 0; padding: 20rpx 8rpx; border-radius: 18rpx; background: #f6f7f8; text-align: center; box-sizing: border-box; }
.metric-item.clickable:active { opacity: .65; }
.metric-value { display: block; color: #17191c; font-size: 38rpx; line-height: 1.1; font-weight: 800; font-variant-numeric: tabular-nums; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.metric-value.amount { font-size: 31rpx; }
.metric-label { display: block; margin-top: 9rpx; color: #878c94; font-size: 20rpx; }
.metric-sub { display:block; margin-top:5rpx; color:#a2a7ae; font-size:17rpx; }

.player-presence { display: flex; align-items: center; justify-content: space-between; gap: 18rpx; margin-top: 14rpx; padding: 19rpx 20rpx; border-radius: 19rpx; background: #17191c; }
.presence-main { min-width: 0; }
.presence-title { display: block; color: #fff; font-size: 25rpx; font-weight: 700; }
.presence-caption { display: block; margin-top: 5rpx; color: rgba(255,255,255,.42); font-size: 19rpx; }
.presence-counts { flex: 0 0 auto; display: flex; align-items: center; gap: 15rpx; }
.presence-count { min-width: 60rpx; text-align: center; }
.presence-count:active { opacity: .65; }
.presence-value { display: block; color: #fff; font-size: 29rpx; font-weight: 800; font-variant-numeric: tabular-nums; }
.presence-count.online .presence-value { color: #b9efcd; }
.presence-label { display: block; margin-top: 3rpx; color: rgba(255,255,255,.4); font-size: 17rpx; }
.presence-divider { width: 1rpx; height: 34rpx; background: rgba(255,255,255,.15); }

.quick-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12rpx 8rpx; }
.quick-action { min-width: 0; min-height: 132rpx; padding: 15rpx 4rpx 10rpx; border-radius: 18rpx; text-align: center; box-sizing: border-box; transition: background-color .16s ease, opacity .16s ease; }
.quick-action:active { opacity: .68; background: #f5f6f7; }
.quick-icon-wrap { position: relative; width: 60rpx; height: 60rpx; margin: 0 auto; display: flex; align-items: center; justify-content: center; border-radius: 18rpx; background: #f4f5f6; }
.quick-icon { width: 34rpx; height: 34rpx; }
.quick-badge { position: absolute; top: -8rpx; right: -16rpx; min-width: 28rpx; height: 28rpx; padding: 0 7rpx; line-height: 28rpx; border-radius: 28rpx; background: #17191c; color: #fff; font-size: 17rpx; box-sizing: border-box; }
.quick-label { display: block; margin-top: 10rpx; color: #383c42; font-size: 22rpx; font-weight: 600; }
.quick-hint { display: block; margin-top: 3rpx; color: #a3a7ad; font-size: 16rpx; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.review-panel { margin-bottom: 2rpx; }
.review-list { overflow: hidden; border: 1rpx solid #eceef1; border-radius: 20rpx; }
.review-row { display: flex; align-items: center; gap: 16rpx; min-height: 104rpx; padding: 17rpx 18rpx; border-bottom: 1rpx solid #eceef1; box-sizing: border-box; }
.review-row:last-child { border-bottom: 0; }
.review-row:active { opacity: .68; background: #f8f9fa; }
.review-mark { flex: 0 0 auto; width: 58rpx; height: 58rpx; line-height: 58rpx; border-radius: 17rpx; text-align: center; font-size: 24rpx; font-weight: 800; }
.review-mark.dark { background: #17191c; color: #fff; }
.review-mark.soft { background: #eceef1; color: #4c5157; }
.review-mark.line { border: 1rpx solid #dadddf; color: #33373d; box-sizing: border-box; }
.review-copy { flex: 1; min-width: 0; }
.review-title { display: block; color: #282c31; font-size: 25rpx; font-weight: 700; }
.review-caption { display: block; margin-top: 5rpx; color: #969ba2; font-size: 19rpx; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.review-side { flex: 0 0 auto; display: flex; align-items: center; gap: 10rpx; }
.review-count { color: #17191c; font-size: 27rpx; font-weight: 800; font-variant-numeric: tabular-nums; }
.review-arrow { color: #b3b7bc; font-size: 30rpx; }

/* Mobile operations dashboard: grouped white cards matching the compact staff reference. */
.dashboard-scroll { background:#f3f4f6; }
.workbench-shell { padding:20rpx 22rpx 0; }
.workbench-hero { padding:24rpx; border:1rpx solid #e9ebee; border-radius:24rpx; background:#fff; box-shadow:0 8rpx 24rpx rgba(23,25,28,.03); }
.hero-orbit { display:none; }
.hero-top { align-items:center; }
.hero-eyebrow { color:#8d939a; font-size:19rpx; letter-spacing:0; }
.hero-title { margin-top:5rpx; color:#181a1d; font-size:34rpx; font-weight:800; }
.hero-subtitle { margin-top:5rpx; color:#a0a4aa; font-size:20rpx; }
.refresh-button { width:58rpx; height:58rpx; min-height:58rpx; line-height:55rpx; border-color:#eceef0; background:#f7f8f9; color:#565c64; font-size:27rpx; }
.shift-panel { margin-top:20rpx; padding:16rpx 18rpx; border-color:#e7e9ec; background:#f7f8f9; }
.shift-panel.online { border-color:#dce9e1; background:#f7fbf8; }
.shift-panel.admin { background:#f7f8f9; }
.shift-dot { background:#a3a8ae; box-shadow:none; }
.shift-panel.online .shift-dot { background:#4f9368; box-shadow:0 0 0 4rpx rgba(79,147,104,.09); }
.shift-title { color:#292d32; font-size:24rpx; }
.shift-loading,.shift-hint { color:#8e949b; }
.shift-error { color:#a3444c; }
.shift-control { background:#fff; color:#697079; border:1rpx solid #e2e5e8; }
.shift-control.active { background:#edf6f0; color:#3f7654; border-color:#d8e8de; }
.shift-knob { background:#b9bdc3; }
.shift-control.active .shift-knob { background:#57936a; }
.shift-readonly-value { color:#17191c; }
.shift-readonly-label { color:#969ba2; }
.pending-heading { margin:22rpx 2rpx 12rpx; }
.pending-title { color:#1c1f22; font-size:26rpx; }
.pending-tip { color:#a0a4aa; }
.pending-grid { gap:10rpx; }
.pending-item { min-height:100rpx; padding:14rpx 4rpx 11rpx; border:1rpx solid #eceef1; background:#f8f9fa; }
.pending-item.alert { background:#fff; border-color:#e2e5e8; }
.pending-item:active { background:#f1f2f4; }
.pending-value { color:#17191c; }
.pending-icon { display:block; width:34rpx; height:34rpx; margin:0 auto 6rpx; opacity:.82; }
.pending-alert-dot { background:#c36a72; }
.pending-label { color:#777d85; }
.pending-skeleton-item { border:1rpx solid #eceef1; background:#f8f9fa; }
.pending-skeleton .skeleton-block { background:linear-gradient(90deg,#eceff2 25%,#f6f7f8 50%,#eceff2 75%); background-size:200% 100%; }
.today-panel .metric-grid { gap:0; }
.today-panel .metric-item { padding:18rpx 6rpx; background:transparent; border-right:1rpx solid #eceef1; border-radius:0; }
.today-panel .metric-item:last-child { border-right:0; }
.player-presence { margin-top:12rpx; background:#f7f8f9; border:1rpx solid #eceef1; }
.presence-title { color:#25292d; }
.presence-caption { color:#969ba2; }
.presence-value { color:#17191c; }
.presence-count.online .presence-value { color:#3c8058; }
.presence-label { color:#92979e; }
.presence-divider { background:#dfe2e5; }
.title-count { color:#8a9097; font-size:23rpx; font-weight:500; }
.product-panel,.action-panel,.review-panel { box-shadow:0 7rpx 22rpx rgba(23,25,28,.025); }
.mobile-action-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:14rpx 8rpx; }
.mobile-action-grid.two { grid-template-columns:repeat(4,minmax(0,1fr)); }
.mobile-action { min-width:0; min-height:132rpx; padding:12rpx 4rpx 8rpx; border-radius:16rpx; text-align:center; box-sizing:border-box; }
.mobile-action:active { background:#f6f7f8; opacity:.72; }
.mobile-action-icon { position:relative; width:64rpx; height:64rpx; margin:0 auto 10rpx; display:flex; align-items:center; justify-content:center; border-radius:18rpx; background:#f3f4f5; }
.mobile-action-icon image { width:36rpx; height:36rpx; }
.mobile-action.accent .mobile-action-icon { background:#eaf3ff; }
.mobile-action > text { display:block; color:#3e4248; font-size:22rpx; font-weight:600; }
.mobile-action-hint { margin-top:3rpx; color:#a1a6ad!important; font-size:16rpx!important; font-weight:400!important; }
.quick-badge { top:-8rpx; right:-12rpx; background:#1d2024; }
.review-list { border:0; border-radius:0; }
.review-row { border-color:#f0f1f3; }
.review-mark.dark { background:#eef0f2; color:#272b30; }

.skeleton-panel { min-height: 230rpx; }
.skeleton-line, .skeleton-block { background: linear-gradient(90deg, #eceff2 25%, #f5f6f7 50%, #eceff2 75%); background-size: 200% 100%; animation: skeleton-sweep 1.35s ease-in-out infinite; border-radius: 8rpx; }
.skeleton-line { height: 20rpx; }
.skeleton-line.title { width: 28%; height: 28rpx; }
.skeleton-line.wide { width: 100%; height: 76rpx; margin-top: 16rpx; border-radius: 16rpx; }
.skeleton-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10rpx; margin-top: 24rpx; }
.skeleton-stat { padding: 18rpx 20rpx; border-radius: 18rpx; background: #f7f8f9; }
.skeleton-stat .value { width: 54%; height: 30rpx; margin: 0 auto 13rpx; }
.skeleton-stat .label { width: 44%; height: 17rpx; margin: 0 auto; }
.pending-skeleton .skeleton-block { background: linear-gradient(90deg, rgba(255,255,255,.06) 25%, rgba(255,255,255,.14) 50%, rgba(255,255,255,.06) 75%); background-size: 200% 100%; animation: skeleton-sweep 1.35s ease-in-out infinite; }

@keyframes skeleton-sweep {
  0% { background-position: 100% 0; }
  100% { background-position: -100% 0; }
}

@media (max-width: 350px) {
  .workbench-shell { padding-left: 18rpx; padding-right: 18rpx; }
  .workbench-hero { padding-left: 22rpx; padding-right: 22rpx; }
  .pending-tip, .quick-hint { display: none; }
  .panel { padding-left: 20rpx; padding-right: 20rpx; }
  .metric-value { font-size: 34rpx; }
  .metric-value.amount { font-size: 27rpx; }
}

@media (prefers-reduced-motion: reduce) {
  .skeleton-line, .skeleton-block { animation: none; }
  .refresh-button.spinning { transform: none; }
}
</style>
