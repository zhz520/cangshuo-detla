<template>
  <view class="cs-mine tab-page">
    <scroll-view scroll-y class="mine-scroll tab-page-scroll" :show-scrollbar="false">
      <view class="mine-shell">
        <view class="user-header" @click="goProfileEdit">
          <AppImage :width="160" class="avatar" :src="csInfo.avatar || '/static/images/default-avatar.png'" mode="aspectFill" />
          <view class="info">
            <view class="name-row">
              <text class="name">{{ csInfo.nickname || '客服工作台' }}</text>
              <text class="role-badge">{{ roleLabel }}</text>
            </view>
            <text class="desc">{{ isAdmin ? '全局运营 · 审核与配置' : '订单服务 · 客户沟通 · 日常审核' }}</text>
          </view>
          <text class="arrow">›</text>
        </view>

        <view
          v-if="!isAdmin"
          class="shift-card"
          :class="{ online: shift.online, unavailable: !shiftReady || shiftError }"
        >
          <view class="shift-copy">
            <view class="shift-title-row">
              <text class="status-dot" />
              <text class="shift-title">{{ shiftTitle }}</text>
              <text v-if="shiftLoading" class="shift-pending">同步中</text>
            </view>
            <text class="shift-caption">{{ shiftCaption }}</text>
            <text v-if="shiftError" class="shift-retry" @click.stop="loadShift">{{ shiftError }} · 重试</text>
          </view>
          <view
            class="shift-switch"
            :class="{ active: shift.online, disabled: shiftLoading || !shiftReady }"
            role="button"
            :aria-disabled="shiftLoading || !shiftReady"
            @click.stop="toggleShift"
          >
            <view class="switch-track"><view class="switch-knob" /></view>
            <text>{{ shift.online ? '上班中' : '已下班' }}</text>
          </view>
        </view>

        <view class="group-card">
          <view class="group-heading"><text>常用管理</text><text>仅显示当前权限</text></view>
        <view v-if="workMenus.length" class="menu-grid">
          <view
            v-for="item in workMenus"
            :key="item.key"
            class="menu-tile"
            role="button"
            @click="openMenu(item)"
          >
            <view class="menu-icon-wrap">
              <image class="menu-icon" :src="item.icon" mode="aspectFit" />
              <view v-if="menuBadge(item) > 0" class="badge">{{ menuBadge(item) > 99 ? '99+' : menuBadge(item) }}</view>
            </view>
            <text class="menu-label">{{ item.label }}</text>
          </view>
        </view>
        <view v-else class="menu-empty">
          <text>{{ permissionsReady ? '当前账号暂无工作管理权限' : '正在加载权限…' }}</text>
        </view>
        </view>

        <view class="group-card">
          <view class="group-heading"><text>账号与服务</text><text>通知、资料与登录</text></view>
        <view class="menu-grid service-grid">
          <view
            v-for="item in serviceMenus"
            :key="item.key"
            class="menu-tile"
            role="button"
            @click="openMenu(item)"
          >
            <view class="menu-icon-wrap">
              <image class="menu-icon" :src="item.icon" mode="aspectFit" />
              <view v-if="menuBadge(item) > 0" class="badge">{{ menuBadge(item) > 99 ? '99+' : menuBadge(item) }}</view>
            </view>
            <text class="menu-label">{{ item.label }}</text>
          </view>
        </view>
        </view>

        <view class="logout" role="button" @click="doLogout">退出登录</view>
        <TechSupportFooter />
        <view class="tab-page-bottom-spacer" />
      </view>
    </scroll-view>
    <CustomTabBar :current="4" />
  </view>
</template>

<script setup>
import AppImage from '@/components/AppImage.vue'
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import CustomTabBar from '@/components/CustomTabBar.vue'
import TechSupportFooter from '@/components/TechSupportFooter.vue'
import { useAppStore } from '@/store/app'
import { useChatStore } from '@/store/chat'
import { useRemindStore } from '@/store/remind'
import { getCsInfo, getCsToken, removeCsToken } from '@/utils/auth'
import { revokeSession } from '@/api/auth'
import { useOfficialWechatStore } from '@/store/official-wechat'
import { getCsPermissions, getCsShiftStatus, toggleCsShift } from '@/pages-cs/api/cs'

const PERMISSION_FALLBACK = ['order.read', 'order.manage', 'player.manage', 'media.review', 'chat.manage', 'user.read']
const appStore = useAppStore()
const remindStore = useRemindStore()
const officialWechat = useOfficialWechatStore()
const csInfo = getCsInfo() || {}
const permissions = ref([])
const permissionsReady = ref(false)
const shift = ref({ online: false })
const shiftReady = ref(false)
const shiftLoading = ref(false)
const shiftError = ref('')
let shiftCache = null
let shiftLoadedAt = 0
let shiftRequest = null

const isAdmin = computed(() => ['ADMIN', 'BOTH'].includes(String(csInfo.role || '').toUpperCase()))
const roleLabel = computed(() => isAdmin.value ? '管理员' : '客服')
const shiftTitle = computed(() => {
  if (!shiftReady.value) return '排班状态连接中'
  return shift.value.online ? '当前正在当班' : '当前已下班'
})
const shiftCaption = computed(() => {
  if (shift.value.online) return '当班期间接收订单、审核、投诉和退款待办'
  return '下班后暂停接收需要人工处理的业务提醒'
})

const workMenus = computed(() => {
  const menus = [
    { key: 'orders', label: '订单管理', caption: '指派、进度与售后', icon: '/static/icons/订单.svg', route: '/pages-cs/order/list', permission: 'order.read', badge: remindStore.workbenchUnread },
    { key: 'complaints', label: '投诉管理', caption: '投诉、仲裁与处理记录', icon: '/static/icons/提示.svg', route: '/pages-cs/complaint/list', permission: 'order.manage', badge: remindStore.complaintUnread },
    { key: 'players', label: '打手管理', caption: '入驻、等级和接单状态', icon: '/static/icons/会员.svg', route: '/pages-cs/player/list', permission: 'player.manage' },
    { key: 'customers', label: '客户管理', caption: '账号状态与客户资料', icon: '/pages-cs/static/icons/会员.svg', route: '/pages-cs/user/list', permission: 'user.read' },
    { key: 'withdraws', label: '提现审核', caption: '待审核提现与处理', icon: '/static/icons/钞票.svg', route: '/pages-cs/withdraw/list', permission: 'withdraw.review' },
    { key: 'products', label: '商品管理', caption: '商品、分类与上下架', icon: '/static/icons/商品.svg', route: '/pages-cs/product/list', permission: 'product.manage' },
    { key: 'quick-reply', label: '快捷发言', caption: '维护客服快捷话术', icon: '/static/icons/快捷.svg', route: '/pages-cs/quick-reply/index', permission: 'chat.manage' }
  ]
  return menus.filter(item => can(item.permission))
})

const serviceMenus = computed(() => {
  const menus = []
  if (officialWechat.isEnabled('cs')) {
    menus.push({
      key: 'official',
      label: '公众号消息通知',
      caption: '绑定微信接收业务通知',
      icon: '/static/icons/短信.svg',
      route: '/pages/wechat/binding?role=cs'
    })
  }
  menus.push({
    key: 'messages',
    label: '系统通知',
    caption: '站内消息与审核结果',
    icon: '/static/icons/提示.svg',
    route: '/pages/message/index',
    badge: remindStore.systemUnread
  })
  menus.push({
    key: 'profile',
    label: '编辑资料',
    caption: '头像、昵称与联系方式',
    icon: '/static/icons/编辑.svg',
    route: '/pages-cs/profile/edit'
  })
  return menus
})

function hasPermission(code) {
  if (isAdmin.value) return true
  if (!permissionsReady.value) return false
  return permissions.value.includes('*') || permissions.value.includes(code)
}

function can(code) {
  return hasPermission(code)
}

function menuBadge(item) {
  return Number(item.badge || 0)
}

async function loadPermissions() {
  if (isAdmin.value) {
    permissions.value = ['*']
    permissionsReady.value = true
    return
  }
  try {
    const response = await getCsPermissions()
    permissions.value = Array.isArray(response.data) ? response.data : []
  } catch (_) {
    permissions.value = [...PERMISSION_FALLBACK]
  } finally {
    permissionsReady.value = true
  }
}

async function loadShift() {
  if (isAdmin.value) return
  if (shiftRequest) return shiftRequest
  if (shiftCache && Date.now() - shiftLoadedAt < 15000) {
    applyShift(shiftCache)
    return
  }
  shiftError.value = ''
  shiftRequest = getCsShiftStatus()
    .then(response => {
      const data = response.data || {}
      applyShift(data)
      shiftCache = data
      shiftLoadedAt = Date.now()
    })
    .catch(() => {
      shiftError.value = '排班状态暂不可用'
    })
    .finally(() => {
      shiftRequest = null
    })
  return shiftRequest
}

function applyShift(payload = {}) {
  const rawOnline = firstBoolean(
    payload.online,
    payload.onDuty,
    payload.isOnline,
    payload.currentOnline,
    payload.shift?.online,
    payload.shift?.onDuty
  )
  if (rawOnline !== null) shift.value = { online: rawOnline }
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

async function toggleShift() {
  if (isAdmin.value || shiftLoading.value || !shiftReady.value) return
  shiftLoading.value = true
  shiftError.value = ''
  const nextOnline = !shift.value.online
  try {
    const response = await toggleCsShift(nextOnline)
    const payload = response.data && Object.keys(response.data).length ? response.data : { online: nextOnline }
    applyShift(payload)
    shiftCache = { online: nextOnline }
    shiftLoadedAt = Date.now()
    uni.showToast({ title: nextOnline ? '已开始上班' : '已下班', icon: 'none' })
  } catch (_) {
    shiftError.value = '上班状态切换失败'
  } finally {
    shiftLoading.value = false
  }
}

onShow(async () => {
  uni.setNavigationBarTitle({ title: isAdmin.value ? '运营管理后台' : '客服工作台' })
  remindStore.fetchCsRemind()
  officialWechat.load('cs')
  await Promise.allSettled([loadPermissions(), loadShift()])
})

function openMenu(item) {
  if (item.route) uni.navigateTo({ url: item.route })
}
function goProfileEdit() { uni.navigateTo({ url: '/pages-cs/profile/edit' }) }

function doLogout() {
  uni.showModal({
    title: '确认退出',
    content: '确定退出客服登录吗？',
    async success(r) {
      if (r.confirm) {
        useChatStore().disconnect()
        const token = getCsToken()
        if (token) await Promise.allSettled([revokeSession(token)])
        removeCsToken()
        appStore.switchToUser()
      }
    }
  })
}
</script>

<style lang="scss" scoped>
.cs-mine {
  background:
    radial-gradient(circle at 90% 0, rgba(255, 255, 255, .98), transparent 32%),
    #f2f3f5;
  height: var(--app-page-height, 100vh);
  position: relative;
  overflow: hidden;
}
.mine-scroll {
  height: calc(var(--app-page-height, 100vh) - 110rpx - var(--app-safe-bottom, env(safe-area-inset-bottom)));
  position: relative;
  z-index: 1;
}
.mine-shell {
  width: 100%;
  max-width: 860rpx;
  margin: 0 auto;
  padding-top: 22rpx;
  box-sizing: border-box;
  overflow-x: hidden;
}
.user-header {
  display: flex;
  align-items: center;
  gap: 22rpx;
  padding: 30rpx 28rpx;
  margin: 0 24rpx;
  background:
    radial-gradient(circle at 100% 0, rgba(255,255,255,.18), transparent 42%),
    linear-gradient(145deg, #15171a, #24282d);
  border-radius: 28rpx;
  box-shadow: 0 18rpx 38rpx rgba(20, 22, 25, .16);
  position: relative;
  z-index: 1;
  box-sizing: border-box;
}
.user-header .avatar {
  width: 104rpx;
  height: 104rpx;
  border-radius: 24rpx;
  border: 2rpx solid rgba(255, 255, 255, .16);
  flex-shrink: 0;
}
.user-header .info { flex: 1; min-width: 0; }
.name-row { display: flex; align-items: center; gap: 10rpx; min-width: 0; }
.name { min-width: 0; max-width: 100%; font-size: 34rpx; font-weight: 800; color: #fff; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.role-badge { flex: 0 0 auto; padding: 5rpx 11rpx; border-radius: 999rpx; background: rgba(255,255,255,.12); color: rgba(255,255,255,.72); font-size: 18rpx; }
.desc { display: block; font-size: 22rpx; color: rgba(255,255,255,.48); margin-top: 9rpx; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.user-header > .arrow { color: rgba(255,255,255,.4); font-size: 34rpx; flex-shrink: 0; }

.shift-card {
  display: flex;
  align-items: center;
  gap: 18rpx;
  margin: 18rpx 24rpx 0;
  padding: 22rpx 24rpx;
  border: 1rpx solid #e7e9ec;
  border-radius: 22rpx;
  background: #fff;
  box-shadow: 0 8rpx 22rpx rgba(23,25,28,.025);
  box-sizing: border-box;
}
.shift-card.online { border-color: #dce9e1; background: #fbfdfc; }
.shift-card.unavailable { border-color: #eee4e5; }
.shift-copy { flex: 1; min-width: 0; }
.shift-title-row { display: flex; align-items: center; gap: 9rpx; }
.status-dot { width: 12rpx; height: 12rpx; border-radius: 50%; background: #a3a8ae; }
.shift-card.online .status-dot { background: #4f9368; box-shadow: 0 0 0 5rpx rgba(79,147,104,.09); }
.shift-title { color: #282c31; font-size: 25rpx; font-weight: 700; }
.shift-pending { color: #8e939a; font-size: 19rpx; }
.shift-caption { display: block; margin-top: 7rpx; color: #8c9198; font-size: 20rpx; line-height: 1.45; }
.shift-retry { display: block; margin-top: 5rpx; color: #9a5158; font-size: 19rpx; }
.shift-switch { flex: 0 0 auto; display: flex; align-items: center; gap: 8rpx; min-height: 52rpx; padding: 0 15rpx 0 8rpx; border-radius: 999rpx; background: #f1f2f3; color: #737880; font-size: 19rpx; transition: opacity .16s ease, background-color .16s ease; }
.shift-switch.active { background: #edf5f0; color: #3f7654; }
.shift-switch.disabled { opacity: .48; pointer-events: none; }
.switch-track { width: 42rpx; height: 24rpx; padding: 3rpx; border-radius: 999rpx; background: #c7cbd0; box-sizing: border-box; }
.switch-knob { width: 18rpx; height: 18rpx; border-radius: 50%; background: #fff; transition: transform .16s ease; }
.shift-switch.active .switch-track { background: #57936a; }
.shift-switch.active .switch-knob { transform: translateX(18rpx); }

.group-title { margin: 28rpx 30rpx 10rpx; color: #8b9097; font-size: 20rpx; letter-spacing: 1rpx; }
.menu-list { margin: 0 24rpx; background: #fff; border: 1rpx solid #e9ebee; border-radius: 24rpx; box-shadow: 0 8rpx 24rpx rgba(23,25,28,.025); overflow: hidden; }
.menu-item { display: flex; align-items: center; gap: 16rpx; min-height: 100rpx; padding: 18rpx 20rpx; border-bottom: 1rpx solid #f0f1f3; box-sizing: border-box; }
.menu-item:last-child { border-bottom: 0; }
.menu-item:active { background: #f8f9fa; }
.menu-icon-wrap { flex: 0 0 auto; width: 58rpx; height: 58rpx; display: flex; align-items: center; justify-content: center; border-radius: 17rpx; background: #f4f5f6; }
.menu-icon { width: 32rpx; height: 32rpx; }
.menu-copy { flex: 1; min-width: 0; }
.menu-label { display: block; color: #34383e; font-size: 26rpx; font-weight: 600; }
.menu-caption { display: block; margin-top: 5rpx; color: #9a9fa6; font-size: 19rpx; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.badge { flex: 0 0 auto; min-width: 30rpx; height: 30rpx; line-height: 30rpx; padding: 0 7rpx; font-size: 18rpx; color: #fff; background: #17191c; border-radius: 30rpx; text-align: center; box-sizing: border-box; }
.arrow { flex: 0 0 auto; color: #b6bac0; font-size: 31rpx; }
.menu-empty { margin: 0 24rpx; padding: 34rpx 24rpx; border: 1rpx dashed #dadde1; border-radius: 22rpx; background: rgba(255,255,255,.65); color: #969ba2; font-size: 22rpx; text-align: center; }
.logout { margin: 34rpx 24rpx 0; height: 86rpx; line-height: 86rpx; text-align: center; background: #fff; color: #b44750; font-size: 27rpx; border: 1rpx solid #eee4e5; border-radius: 22rpx; }
.logout:active { opacity: .68; background: #fff9f9; }

/* Compact mobile staff settings matching the card-based operations workspace. */
.cs-mine { background:#f3f4f6; }
.mine-shell { padding-top:20rpx; }
.user-header { padding:24rpx; background:#fff; border:1rpx solid #e9ebee; border-radius:24rpx; box-shadow:0 7rpx 22rpx rgba(23,25,28,.025); }
.user-header .avatar { width:92rpx; height:92rpx; border-radius:22rpx; border:1rpx solid #edf0f2; }
.name { color:#17191c; font-size:31rpx; }
.role-badge { background:#f0f2f4; color:#666d75; }
.desc { color:#969ca3; margin-top:7rpx; }
.user-header > .arrow { color:#b8bdc3; }
.shift-card { margin-top:16rpx; border-radius:22rpx; box-shadow:0 7rpx 22rpx rgba(23,25,28,.025); }
.group-card { margin:18rpx 24rpx 0; padding:22rpx 20rpx 18rpx; background:#fff; border:1rpx solid #e9ebee; border-radius:24rpx; box-shadow:0 7rpx 22rpx rgba(23,25,28,.025); }
.group-heading { display:flex; align-items:baseline; justify-content:space-between; gap:12rpx; margin:0 4rpx 14rpx; }
.group-heading text:first-child { color:#1b1e21; font-size:27rpx; font-weight:800; }
.group-heading text:last-child { color:#a1a6ad; font-size:17rpx; }
.menu-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:6rpx 2rpx; }
.menu-grid.service-grid { grid-template-columns:repeat(3,minmax(0,1fr)); }
.menu-tile { min-width:0; min-height:132rpx; padding:10rpx 4rpx 8rpx; border-radius:16rpx; text-align:center; box-sizing:border-box; }
.menu-tile:active { background:#f6f7f8; opacity:.72; }
.menu-tile .menu-icon-wrap { position:relative; width:66rpx; height:66rpx; margin:0 auto 9rpx; border-radius:18rpx; background:#f3f4f5; }
.menu-tile .menu-icon { width:36rpx; height:36rpx; }
.menu-tile .menu-label { display:block; color:#3d4248; font-size:20rpx; font-weight:600; line-height:1.25; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.menu-tile .badge { position:absolute; top:-8rpx; right:-10rpx; min-width:28rpx; height:28rpx; line-height:28rpx; padding:0 7rpx; font-size:17rpx; }
.group-card .menu-empty { margin:0; padding:28rpx 18rpx; }
.logout { margin-top:20rpx; height:78rpx; line-height:78rpx; border-radius:20rpx; }

@media (max-width: 350px) {
  .user-header, .shift-card, .group-card, .menu-list, .menu-empty, .logout { margin-left: 18rpx; margin-right: 18rpx; }
  .shift-card { padding-left: 19rpx; padding-right: 19rpx; }
  .shift-switch { padding-right: 10rpx; }
}

@media (prefers-reduced-motion: reduce) {
  .shift-switch, .switch-knob { transition: none; }
}
</style>
