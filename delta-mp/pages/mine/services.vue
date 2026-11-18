<template>
  <view class="services-page">
    <ScreenHeader title="设置" back center />
    <scroll-view scroll-y class="page-scroll" :show-scrollbar="false">
      <view class="page-content">
        <view class="group-card">
          <text class="group-title">账户与资产</text>
          <view class="menu-row" @click="goProtected('/pages/wallet/index')">
            <image class="menu-icon" src="/static/icons/svc-wallet.svg" mode="aspectFit" />
            <text class="menu-text">我的钱包</text><text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="goProtected('/pages/membership/index')">
            <image class="menu-icon" src="/static/icons/svc-member.svg" mode="aspectFit" />
            <text class="menu-text">会员等级</text><text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="goProtected('/pages/invites/index')">
            <image class="menu-icon" src="/static/icons/svc-invite.svg" mode="aspectFit" />
            <text class="menu-text">邀请好友</text><text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="go('/pages/display/index?tab=rank')">
            <image class="menu-icon" src="/static/icons/svc-rank.svg" mode="aspectFit" />
            <text class="menu-text">消费排行</text><text class="menu-arrow">›</text>
          </view>
        </view>

        <view class="group-card">
          <text class="group-title">账户安全</text>
          <view class="menu-row" @click="goProtected('/pages/mine/security')">
            <image class="menu-icon" src="/static/icons/svc-privacy.svg" mode="aspectFit" />
            <text class="menu-text">账号安全</text><text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="goProtected('/pages/mine/identity')">
            <image class="menu-icon" src="/static/icons/svc-identity.svg" mode="aspectFit" />
            <text class="menu-text">实名认证</text>
            <text class="menu-state">{{ identityLabel }}</text>
            <text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="goProtected('/pages/message/index')">
            <image class="menu-icon" src="/static/icons/header-bell.svg" mode="aspectFit" />
            <text class="menu-text">通知</text>
            <text v-if="systemUnread > 0" class="menu-badge">{{ systemUnread > 99 ? '99+' : systemUnread }}</text>
            <text class="menu-arrow">›</text>
          </view>
          <view v-if="officialWechat.isEnabled('user')" class="menu-row" @click="goProtected('/pages/wechat/binding')">
            <image class="menu-icon" src="/static/icons/svc-wechat.svg" mode="aspectFit" />
            <text class="menu-text">公众号通知</text><text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="goProtected('/pages/mine/privacy-center')">
            <image class="menu-icon" src="/static/icons/svc-privacy.svg" mode="aspectFit" />
            <text class="menu-text">隐私与注销</text><text class="menu-arrow">›</text>
          </view>
        </view>

        <view class="group-card">
          <text class="group-title">服务与帮助</text>
          <view class="menu-row" @click="goProtected('/pages/mine/library')">
            <image class="menu-icon" src="/static/icons/svc-library.svg" mode="aspectFit" />
            <text class="menu-text">收藏与关注</text><text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="go('/pages/complaint/list')">
            <image class="menu-icon" src="/static/icons/svc-complaint.svg" mode="aspectFit" />
            <text class="menu-text">投诉工单</text><text class="menu-arrow">›</text>
          </view>
          <view v-if="siteStore.guestCheckoutEnabled" class="menu-row" @click="go('/pages/order/lookup')">
            <image class="menu-icon" src="/static/icons/svc-guest.svg" mode="aspectFit" />
            <text class="menu-text">游客订单</text><text class="menu-arrow">›</text>
          </view>
        </view>

        <view class="group-card">
          <text class="group-title">关于与设置</text>
          <!-- #ifdef H5 -->
          <view v-if="siteStore.userThemeAllowed" class="menu-row" @click="chooseTheme">
            <text class="menu-text">外观</text><text class="menu-state">{{ themeLabels[siteStore.userTheme] }}</text><text class="menu-arrow">›</text>
          </view>
          <!-- #endif -->
          <view class="menu-row" @click="go('/pages/agreement/user')">
            <image class="menu-icon" src="/static/icons/svc-info.svg" mode="aspectFit" />
            <text class="menu-text">用户协议</text><text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="go('/pages/agreement/privacy')">
            <image class="menu-icon" src="/static/icons/svc-privacy.svg" mode="aspectFit" />
            <text class="menu-text">隐私政策</text><text class="menu-arrow">›</text>
          </view>
          <view class="menu-row" @click="go('/pages/agreement/about')">
            <image class="menu-icon" src="/static/icons/svc-info.svg" mode="aspectFit" />
            <text class="menu-text">关于我们</text><text class="menu-arrow">›</text>
          </view>
          <view v-if="userStore.isLoggedIn" class="menu-row danger" @click="logout">
            <image class="menu-icon" src="/static/icons/svc-logout.svg" mode="aspectFit" />
            <text class="menu-text">退出登录</text>
          </view>
          <view v-if="userStore.isGuest" class="menu-row danger" @click="exitGuest">
            <image class="menu-icon" src="/static/icons/svc-logout.svg" mode="aspectFit" />
            <text class="menu-text">退出游客会话</text>
          </view>
        </view>

        <TechSupportFooter />
      </view>
    </scroll-view>
  </view>
</template>
<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import ScreenHeader from '@/components/ScreenHeader.vue'
import TechSupportFooter from '@/components/TechSupportFooter.vue'
import { useUserStore } from '@/store/user'
import { useSiteStore } from '@/store/site'
import { useAppStore } from '@/store/app'
import { useChatStore } from '@/store/chat'
import { useRemindStore } from '@/store/remind'
import { useOfficialWechatStore } from '@/store/official-wechat'
import { setPlayerToken } from '@/utils/auth'
import { switchToPlayerToken } from '@/api/auth'
import { getPlayerApplicationStatus } from '@/api/player'
import { getIdentityStatus } from '@/api/user'

const userStore = useUserStore()
const siteStore = useSiteStore()
const themeLabels = { '': '跟随站点', default: '浅色', dark: '暗黑' }
function chooseTheme() {
  const choices = ['', ...siteStore.userThemeOptions]
  uni.showActionSheet({ itemList: choices.map(id => themeLabels[id]), success: ({ tapIndex }) => siteStore.setUserTheme(choices[tapIndex]) })
}
const appStore = useAppStore()
const chatStore = useChatStore()
const remindStore = useRemindStore()
const officialWechat = useOfficialWechatStore()
const identityStatus = ref('')
const playerStatus = ref('')
const systemUnread = computed(() => remindStore.systemUnread)
const identityLabel = computed(() => ({ APPROVED: '已认证', PENDING: '审核中', REJECTED: '未通过', DRAFT: '待提交', NOT_SUBMITTED: '未认证' }[identityStatus.value] || '未认证'))
const playerEntry = computed(() => {
  if (!userStore.isLoggedIn) return { title: '登录后入驻接单员端', desc: '登录后申请接单、查看收益和提现', action: '登录' }
  if (playerStatus.value === 'ACTIVE') return { title: '接单员工作台', desc: '切换身份接单、查看收益和提现', action: '进入' }
  if (playerStatus.value === 'PENDING') return { title: '接单员申请审核中', desc: '审核通过后即可进入接单员端', action: '查看' }
  if (playerStatus.value === 'FROZEN') return { title: '接单员账号已冻结', desc: '请联系客服处理后再接单', action: '查看' }
  if (playerStatus.value === 'REJECTED') return { title: '重新申请接单员', desc: '补充资料后可重新提交入驻申请', action: '申请' }
  return { title: '入驻接单员端', desc: '通过审核后可接单、查看收益和提现', action: '进入' }
})

onShow(() => {
  userStore.syncSessionFromStorage()
  if (userStore.isLoggedIn) officialWechat.load('user')
  if (!userStore.token) return
  getIdentityStatus({ loading: false }).then(res => {
    identityStatus.value = res.data?.status || ''
  }).catch(() => { identityStatus.value = '' })
  loadPlayerEntry()
})

async function loadPlayerEntry() {
  try {
    const { data } = await getPlayerApplicationStatus()
    playerStatus.value = data?.status || ''
  } catch (e) {
    if (e?.data?.code === 1002 || e?.code === 1002) playerStatus.value = ''
  }
}

function go(url) {
  const tabPages = ['/pages/index/index', '/pages/category/index', '/pages/players/index', '/pages/chat/list', '/pages/mine/index']
  if (tabPages.includes(url)) uni.switchTab({ url })
  else uni.navigateTo({ url })
}
function goProtected(url) {
  if (!userStore.checkLogin()) return
  go(url)
}
function logout() { userStore.logout() }
function exitGuest() { userStore.exitGuest() }
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
    if (e?.data?.code === 1002) uni.navigateTo({ url: '/pages-player/apply/index' })
  }
}
</script>
<style lang="scss" scoped>
.services-page { height: var(--app-page-height, 100vh); display: flex; flex-direction: column; overflow: hidden; background: var(--store-soft); }
.page-scroll { flex: 1; height: 0; min-height: 0; width: 100%; }
.page-content { padding: 10rpx 28rpx calc(50rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); box-sizing: border-box; }
.group-card { margin-bottom: 18rpx; padding: 8rpx 24rpx; border-radius: 26rpx; background: var(--store-surface); }
.group-title { display: block; padding: 22rpx 2rpx 10rpx; color: var(--store-muted); font-size: 22rpx; font-weight: 650; letter-spacing: .02em; }
.menu-row { display: flex; align-items: center; gap: 20rpx; min-height: 102rpx; border-bottom: 1rpx solid #f3f3f3; }
.menu-row:last-child { border-bottom: 0; }
.menu-row:active { opacity: .7; }
.menu-icon { width: 42rpx; height: 42rpx; flex-shrink: 0; opacity: .9; }
.menu-text { flex: 1; min-width: 0; color: var(--store-text); font-size: 28rpx; font-weight: 650; }
.menu-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.menu-desc { overflow: hidden; color: var(--store-muted); font-size: 21rpx; text-overflow: ellipsis; white-space: nowrap; }
.menu-state { flex-shrink: 0; color: var(--store-muted); font-size: 22rpx; }
.menu-badge { flex-shrink: 0; min-width: 30rpx; height: 30rpx; padding: 0 8rpx; border-radius: 999rpx; background: #e24d4d; color: #ffffff; font-size: 18rpx; line-height: 30rpx; text-align: center; box-sizing: border-box; }
.menu-arrow { flex-shrink: 0; color: #bbbbbb; font-size: 34rpx; line-height: 1; }
.danger .menu-text { color: #b84851; }
.danger .menu-icon { opacity: .65; }
:deep(.tech-support) { margin-top: 26rpx; }
</style>
