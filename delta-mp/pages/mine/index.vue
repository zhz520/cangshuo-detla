<template>
  <view class="mine-page tab-page">
    <ScreenHeader title="我的" center>
      <view class="screen-icon-btn" role="button" aria-label="账号设置" @click="goSettings">
        <image src="/static/icons/header-settings.svg" mode="aspectFit" />
      </view>
    </ScreenHeader>

    <scroll-view data-delta-content="tab" scroll-y class="mine-scroll" :show-scrollbar="false">
      <view class="mine-content">
        <view class="profile-row" @click="userStore.isLoggedIn ? go('/pages/mine/profile') : goLogin()">
          <AppImage :width="240" class="avatar" :src="userStore.avatar || '/static/images/delta-default-avatar.png'" mode="aspectFill" />
          <view class="profile-info">
            <text class="profile-name">{{ profileName }}</text>
            <view class="profile-meta-row">
              <text class="profile-meta">{{ profileMeta }}</text>
              <view v-if="uid" class="copy-uid" role="button" aria-label="复制UID" @click.stop="copyUid">
                <image src="/static/icons/svc-copy.svg" mode="aspectFit" />
                <text>复制</text>
              </view>
            </view>
          </view>
          <text class="profile-arrow">›</text>
        </view>

        <view class="balance-card" @click="goWallet">
          <view class="balance-head">
            <text class="balance-label">账户余额</text>
            <text class="balance-hint">可用于余额支付</text>
          </view>
          <view class="balance-main">
            <text class="balance-amount">{{ displayBalance }}</text>
            <view class="recharge-btn" @click.stop="goRecharge">{{ userStore.isLoggedIn ? '充值' : '登录' }}</view>
          </view>
        </view>

        <view class="panel orders-panel">
          <view class="order-grid">
            <view class="order-shortcut" @click="goOrderTab('pending')">
              <view class="shortcut-icon"><image src="/static/icons/order-pending.svg" mode="aspectFit" /></view>
              <text>待支付</text>
            </view>
            <view class="order-shortcut" @click="goOrderTab('active')">
              <view class="shortcut-icon"><image src="/static/icons/order-progress.svg" mode="aspectFit" /></view>
              <text>进行中</text>
            </view>
            <view class="order-shortcut" @click="goOrderTab('completed')">
              <view class="shortcut-icon"><image src="/static/icons/order-done.svg" mode="aspectFit" /></view>
              <text>已完成</text>
            </view>
            <view class="order-shortcut" @click="goOrderTab('aftersale')">
              <view class="shortcut-icon"><image src="/static/icons/order-after.svg" mode="aspectFit" /></view>
              <text>售后</text>
            </view>
          </view>
        </view>

        <view class="panel menu-panel">
          <view class="menu-row" @click="goOrders">
            <image class="menu-icon" src="/static/icons/menu-order.svg" mode="aspectFit" />
            <text class="menu-text">我的订单</text>
            <text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="goAccount('/pages/mine/library')">
            <image class="menu-icon" src="/static/icons/menu-heart.svg" mode="aspectFit" />
            <text class="menu-text">我的收藏</text>
            <text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="goAccount('/pages/coupons/index')">
            <image class="menu-icon" src="/static/icons/menu-coupon.svg" mode="aspectFit" />
            <text class="menu-text">优惠券</text>
            <text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="goOrderTab('completed')">
            <image class="menu-icon" src="/static/icons/menu-review.svg" mode="aspectFit" />
            <text class="menu-text">服务评价</text>
            <text class="menu-arrow">›</text>
          </view>
        </view>

        <view class="panel menu-panel">
          <view v-if="userStore.isLoggedIn" class="menu-row" @click="goPlayer">
            <image class="menu-icon" src="/static/icons/svc-player.svg" mode="aspectFit" />
            <view class="menu-copy">
              <text class="menu-text">{{ playerEntry.title }}</text>
              <text class="menu-desc">{{ playerEntry.desc }}</text>
            </view>
            <text class="menu-state">{{ playerEntry.action }}</text>
            <text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="goCustomerService">
            <image class="menu-icon" src="/static/icons/menu-service.svg" mode="aspectFit" />
            <text class="menu-text">客服中心</text>
            <text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="goHelp">
            <image class="menu-icon" src="/static/icons/menu-help.svg" mode="aspectFit" />
            <text class="menu-text">帮助与反馈</text>
            <text class="menu-arrow">›</text>
          </view>
        </view>

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
import { useUserStore } from '@/store/user'
import { useSiteStore } from '@/store/site'
import { useChatStore } from '@/store/chat'
import { useAppStore } from '@/store/app'
import { getUserToken, setPlayerToken } from '@/utils/auth'
import { getWallet } from '@/api/user'
import { getPlayerApplicationStatus } from '@/api/player'
import { switchToPlayerToken } from '@/api/auth'
import { openCustomerService } from '@/utils/customer-service'
import CustomTabBar from '@/components/CustomTabBar.vue'
import ScreenHeader from '@/components/ScreenHeader.vue'
import TechSupportFooter from '@/components/TechSupportFooter.vue'

const userStore = useUserStore()
const siteStore = useSiteStore()
const chatStore = useChatStore()
const appStore = useAppStore()
const balance = ref('0.00')
const playerStatus = ref('')
const uid = computed(() => userStore.isLoggedIn && userStore.userInfo?.id ? String(userStore.userInfo.id) : '')
const profileName = computed(() => userStore.isLoggedIn ? userStore.nickname : (userStore.isGuest ? (userStore.guestInfo?.nickname || '游客') : '点击登录'))
const profileMeta = computed(() => {
  if (userStore.isLoggedIn) return uid.value ? 'UID: ' + uid.value : '完善资料，方便接收服务通知'
  if (userStore.isGuest) return (userStore.guestInfo?.maskedPhone || '游客会话') + ' · 点击登录正式账号'
  return '登录后查看订单和钱包'
})
const displayBalance = computed(() => userStore.isLoggedIn ? '¥' + Number(balance.value || 0).toFixed(2) : '¥—')
const playerEntry = computed(() => {
  if (playerStatus.value === 'ACTIVE') return { title: '接单员工作台', desc: '切换身份接单、查看收益和提现', action: '进入' }
  if (playerStatus.value === 'PENDING') return { title: '接单员申请审核中', desc: '审核通过后即可进入接单员端', action: '查看' }
  if (playerStatus.value === 'FROZEN') return { title: '接单员账号已冻结', desc: '请联系客服处理后再接单', action: '查看' }
  if (playerStatus.value === 'REJECTED') return { title: '重新申请接单员', desc: '补充资料后可重新提交入驻申请', action: '申请' }
  return { title: '入驻接单员端', desc: '通过审核后可接单、查看收益和提现', action: '进入' }
})

onShow(() => {
  userStore.syncSessionFromStorage()
  const savedToken = getUserToken()
  if (savedToken && !userStore.token) userStore.token = savedToken
  if (userStore.token) {
    userStore.fetchProfile(true)
    chatStore.fetchMessageUnreadCount()
    getPlayerApplicationStatus().then(res => { playerStatus.value = res.data?.status || '' }).catch(() => {})
    getWallet({ loading: false }).then(res => { balance.value = Number(res.data?.balance || 0).toFixed(2) }).catch(() => {})
  }
})

const TAB_PAGES = ['/pages/index/index', '/pages/category/index', '/pages/players/index', '/pages/chat/list', '/pages/mine/index']
function go(url) {
  if (TAB_PAGES.includes(url)) uni.switchTab({ url })
  else uni.navigateTo({ url })
}
function goOrders() {
  if (userStore.isLoggedIn || userStore.isGuest) return go('/pages/order/list')
  if (siteStore.guestCheckoutEnabled) return go('/pages/order/lookup')
  uni.showToast({ title: '请先登录后查看订单', icon: 'none' })
}
function goLogin() { uni.navigateTo({ url: '/pages/login/index' }) }
function goAccount(url) { if (!userStore.checkLogin()) return; go(url) }
function goSettings() { if (!userStore.isLoggedIn) return goLogin(); go('/pages/mine/services') }
function goWallet() { if (!userStore.isLoggedIn) return goLogin(); go('/pages/wallet/index') }
function goRecharge() { if (!userStore.isLoggedIn) return goLogin(); go('/pages/wallet/recharge') }
function goOrderTab(tab) {
  if (!userStore.isLoggedIn && !userStore.isGuest) return goOrders()
  uni.navigateTo({ url: '/pages/order/list?tab=' + encodeURIComponent(tab) })
}
function goHelp() { go('/pages/complaint/list') }
async function goPlayer() {
  if (!userStore.checkLogin()) return
  try {
    const { data } = await getPlayerApplicationStatus()
    if (!data?.registered) uni.navigateTo({ url: '/pages-player/apply/index' })
    else if (data.status === 'PENDING') uni.showToast({ title: '审核中，请耐心等待', icon: 'none' })
    else if (data.status === 'ACTIVE') {
      const res = await switchToPlayerToken()
      if (res?.data?.token) {
        setPlayerToken(res.data.token)
        chatStore.disconnect()
        appStore.switchToPlayer()
      }
    } else if (data.status === 'FROZEN') uni.showToast({ title: '接单账号已冻结，请联系客服', icon: 'none' })
    else if (data.status === 'REJECTED') uni.navigateTo({ url: '/pages-player/apply/index' })
    else uni.showToast({ title: '接单账号状态异常，请联系客服', icon: 'none' })
  } catch (e) {
    const msg = e?.data?.msg || e?.message
    if (msg) uni.showToast({ title: msg, icon: 'none' })
  }
}
function copyUid() {
  if (!uid.value) return
  uni.setClipboardData({ data: uid.value, success: () => uni.showToast({ title: 'UID已复制', icon: 'none' }) })
}
async function goCustomerService() {
  try { await openCustomerService() }
  catch (e) { uni.showToast({ title: e?.msg || '连接客服失败', icon: 'none' }) }
}
</script>
<style lang="scss" scoped>
.mine-page { height: var(--app-page-height, 100vh); min-height: var(--app-page-height, 100vh); display: flex; flex-direction: column; overflow: hidden; background: var(--store-soft); }
.mine-scroll { flex: 1; height: 0; min-height: 0; width: 100%; }
.mine-content { padding: 10rpx 28rpx calc(196rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); box-sizing: border-box; }
.profile-row { display: flex; align-items: center; gap: 22rpx; padding: 10rpx 2rpx 28rpx; }
.avatar { width: 120rpx; height: 120rpx; flex-shrink: 0; border-radius: 28rpx; background: var(--store-soft); }
.profile-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10rpx; }
.profile-name { color: var(--store-text); font-size: 40rpx; font-weight: 850; line-height: 1.2; letter-spacing: -0.02em; }
.profile-meta-row { display: flex; align-items: center; gap: 14rpx; min-width: 0; }
.profile-meta { overflow: hidden; color: var(--store-regular); font-size: 23rpx; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.copy-uid { display: inline-flex; align-items: center; gap: 4rpx; flex-shrink: 0; color: var(--store-regular); font-size: 20rpx; }
.copy-uid image { width: 28rpx; height: 28rpx; }
.profile-arrow { flex-shrink: 0; color: #bbbbbb; font-size: 40rpx; line-height: 1; }
.balance-card {
  position: relative;
  overflow: hidden;
  padding: 30rpx 28rpx 28rpx;
  border-radius: 28rpx;
  background: var(--store-primary);
  color: #ffffff;
  box-sizing: border-box;
}
.balance-card::after {
  content: '';
  position: absolute;
  right: -80rpx;
  bottom: -120rpx;
  width: 360rpx;
  height: 360rpx;
  border-radius: 50%;
  background: radial-gradient(circle at 35% 35%, rgba(255,255,255,.16), rgba(255,255,255,0) 68%);
  pointer-events: none;
}
.balance-head { position: relative; z-index: 1; display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.balance-label { font-size: 26rpx; font-weight: 700; }
.balance-hint { color: rgba(255,255,255,.52); font-size: 21rpx; }
.balance-main { position: relative; z-index: 1; display: flex; align-items: flex-end; justify-content: space-between; gap: 18rpx; margin-top: 22rpx; }
.balance-amount { font-size: 56rpx; font-weight: 850; line-height: 1; font-variant-numeric: tabular-nums; }
.recharge-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 122rpx;
  height: 60rpx;
  padding: 0 20rpx;
  border-radius: 999rpx;
  background: var(--store-surface);
  color: var(--store-text);
  font-size: 23rpx;
  font-weight: 800;
}
.panel { margin-top: 18rpx; border: 0; border-radius: 26rpx; background: var(--store-surface); box-shadow: none; }
.orders-panel { padding: 26rpx 0; }
.order-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); }
.order-shortcut {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
  padding: 2rpx;
  color: var(--store-regular);
  font-size: 23rpx;
}
.order-shortcut::after {
  content: '';
  position: absolute;
  top: 8rpx;
  right: 0;
  bottom: 8rpx;
  width: 1rpx;
  background: var(--store-soft);
}
.order-shortcut:last-child::after { display: none; }
.shortcut-icon { display: flex; align-items: center; justify-content: center; width: 48rpx; height: 48rpx; background: transparent; }
.shortcut-icon image { width: 46rpx; height: 46rpx; }
.menu-panel { padding: 4rpx 24rpx; }
.menu-row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  min-height: 104rpx;
  border-bottom: 1rpx solid #f3f3f3;
  &:last-child { border-bottom: 0; }
  &:active { opacity: .7; }
}
.menu-icon { width: 44rpx; height: 44rpx; flex-shrink: 0; }
.menu-text { flex: 1; min-width: 0; color: var(--store-text); font-size: 28rpx; font-weight: 650; }
.menu-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.menu-desc { overflow: hidden; color: var(--store-muted); font-size: 21rpx; text-overflow: ellipsis; white-space: nowrap; }
.menu-state { flex-shrink: 0; color: var(--store-muted); font-size: 21rpx; }
.menu-arrow { flex-shrink: 0; color: #bbbbbb; font-size: 34rpx; line-height: 1; }
:deep(.tech-support) { margin-top: 26rpx; }
</style>
