<template>
  <view class="player-mine tab-page">
    <scroll-view scroll-y class="mine-scroll tab-page-scroll" :show-scrollbar="false">
      <PageState :loading="loading && !loaded" :error="loadError" @retry="loadDashboard" />
      <template v-if="loaded">
      <PlayerOverview :profile="playerStore.playerInfo" :data="homeData" @navigate="go" />
      <WorkbenchEntry />
      <!-- 在线状态 -->
      <view class="online-bar">
        <text class="online-label">接单状态</text>
        <view class="online-right">
          <text :class="['online-status-text', isOnline ? 'on' : 'off']">{{ toggling ? '正在切换…' : isOnline ? '在线接单中' : '休息中' }}</text>
          <switch :checked="isOnline" :disabled="toggling" color="#347a5b" @change="onToggleOnline" />
        </view>
      </view>
      <!-- 菜单 -->
      <text class="group-heading">工作与管理</text>
      <view class="menu-list">
        <view v-if="officialWechat.isEnabled('player')" class="menu-item" @click="go('/pages/wechat/binding?role=player')"><image class="menu-icon" src="/static/icons/短信.svg" mode="aspectFit" /><text>公众号消息通知</text><text class="arrow">›</text></view>
      <view class="menu-item" @click="go('/pages-player/profile/introduction')"><image class="menu-icon" src="/static/icons/订单.svg" mode="aspectFit" /><text>我的主页管理</text><text class="arrow">›</text></view>
        <view class="menu-item" @click="go('/pages-player/withdraw/list')"><image class="menu-icon" src="/static/icons/暂无纪录.svg" mode="aspectFit" /><text>提现记录</text><text class="arrow">›</text></view>
        <view class="menu-item" @click="go('/pages-player/account/list')"><image class="menu-icon" src="/static/icons/理财.svg" mode="aspectFit" /><text>提现账户</text><text class="arrow">›</text></view>
        <view class="menu-item" @click="go('/pages-player/invite/list')">
          <image class="menu-icon" src="/pages-player/static/icons/分享.svg" mode="aspectFit" />
          <text>邀请列表</text>
          <view v-if="remindStore.inviteCount > 0" class="badge">{{ remindStore.inviteCount > 99 ? '99+' : remindStore.inviteCount }}</view>
          <text class="arrow">›</text>
        </view>
        <view class="menu-item" @click="go('/pages/message/index')">
          <image class="menu-icon" src="/static/icons/提示.svg" mode="aspectFit" />
          <text>系统通知</text>
          <view v-if="remindStore.systemUnread > 0" class="badge">{{ remindStore.systemUnread > 99 ? '99+' : remindStore.systemUnread }}</view>
          <text class="arrow">›</text>
        </view>
      </view>
      <!-- 待处理订单预览 -->
      <view class="section" v-if="recentOrders.length">
        <view class="section-header">
          <text class="section-title">待处理订单</text>
          <text class="more" @click="go('/pages-player/order/list')">查看全部 ›</text>
        </view>
        <OrderCard v-for="o in recentOrders" :key="o.id" :order="o" detail-path="/pages-player/order/detail" />
      </view>
      <view class="switch-btn" @click="backToUser">返回用户端</view>
      </template>
      <TechSupportFooter />
      <view class="tab-page-bottom-spacer" />
    </scroll-view>
    <CustomTabBar :current="3" />
  </view>
</template>
<script setup>
import WorkbenchEntry from '@/components/WorkbenchEntry.vue'
import PlayerOverview from '@/components/PlayerOverview.vue'
import PageState from '@/components/PageState.vue'
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import CustomTabBar from '@/components/CustomTabBar.vue'
import TechSupportFooter from '@/components/TechSupportFooter.vue'
import OrderCard from '@/components/OrderCard.vue'
import { usePlayerStore } from '@/store/player'
import { useAppStore } from '@/store/app'
import { useChatStore } from '@/store/chat'
import { useRemindStore } from '@/store/remind'
import { getPlayerHome, getMyWork, toggleOnlineStatus } from '@/api/player'
import { useOfficialWechatStore } from '@/store/official-wechat'

const playerStore = usePlayerStore()
const appStore = useAppStore()
const remindStore = useRemindStore()
const officialWechat = useOfficialWechatStore()
const homeData = ref({})
const recentOrders = ref([])
const loading = ref(false), loaded = ref(false), loadError = ref(''), toggling = ref(false)

const isOnline = computed(() => playerStore.playerInfo?.isOnline === 1)

onShow(() => {
  try { uni.hideHomeButton() } catch (_) {}
  officialWechat.load('player')
  remindStore.fetchPlayerRemind()
  loadDashboard()
})
async function loadDashboard() {
  if (loading.value) return
  loading.value = true; loadError.value = ''
  const [home, work] = await Promise.allSettled([
    getPlayerHome({ loading: false }),
    getMyWork({ pageNum: 1, pageSize: 3, status: 'ASSIGNED' }, { loading: false }),
    playerStore.fetchProfile({ loading: false })
  ])
  if (home.status === 'fulfilled') { homeData.value = home.value.data || {}; loaded.value = true }
  else loadError.value = '工作台数据暂未获取，请检查网络后重试。'
  if (work.status === 'fulfilled') recentOrders.value = work.value.data?.records || []
  loading.value = false
}

async function onToggleOnline(e) {
  const newVal = e.detail.value
  if (toggling.value) return
  toggling.value = true
  try {
    await toggleOnlineStatus(newVal)
    if (playerStore.playerInfo) playerStore.playerInfo.isOnline = newVal ? 1 : 0
    uni.showToast({ title: newVal ? '已上线' : '已下线', icon: 'none' })
  } catch (err) {
    await playerStore.fetchProfile({ loading: false, force: true })
  } finally { toggling.value = false }
}

function go(url) {
  if (['/pages-player/hall/index', '/pages-player/order/list'].includes(url)) uni.redirectTo({ url })
  else uni.navigateTo({ url })
}
function backToUser() {
  useChatStore().disconnect()
  appStore.switchToUser()
}
</script>
<style lang="scss" scoped>
.player-mine { background: #f7f7f7; position: relative; }
.online-bar { display:flex; align-items:center; justify-content:space-between; padding:22rpx 26rpx; margin:24rpx 28rpx 0; background:#fff; border:1rpx solid #edf0f3; border-radius:24rpx; }
.online-label { font-size:27rpx; color:#343a43; font-weight:600; }
.online-right { display:flex; align-items:center; gap:12rpx; }
.online-status-text { font-size:24rpx; color:#777777; } .online-status-text.on { color:#347a5b; }
.group-heading { display:block; margin:34rpx 34rpx 16rpx; color:#777777; font-size:23rpx; letter-spacing:2rpx; }
.menu-list { margin:0 28rpx; background:#fff; border:1rpx solid #edf0f3; border-radius:24rpx; overflow:hidden; }
.menu-item { display:flex; align-items:center; padding:28rpx 26rpx; border-bottom:1rpx solid #f1f3f5; font-size:27rpx; gap:18rpx; }
.menu-item:last-child { border-bottom:0; }
.menu-icon { width:38rpx; height:38rpx; flex-shrink:0; }
.menu-item text:not(.arrow) { flex:1; }
.arrow { color:#b1b8c3; margin-left:auto; }
.badge { min-width:32rpx; height:32rpx; line-height:32rpx; padding:0 8rpx; font-size:20rpx; color:#fff; background:#d44955; border-radius:32rpx; text-align:center; }
.section { padding:28rpx; }
.section-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:18rpx; }
.section-title { font-size:29rpx; font-weight:600; } .more { font-size:24rpx; color:#777777; }
.switch-btn { margin:28rpx; padding:24rpx; text-align:center; background:#fff; color:#525a66; font-size:27rpx; border-radius:24rpx; border:1rpx solid #e4e8ed; }
</style>
