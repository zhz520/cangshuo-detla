<template>
  <view class="mine-page tab-page">
    <scroll-view scroll-y class="mine-scroll tab-page-scroll" :show-scrollbar="false">
      <view class="profile-card" @click="userStore.isLoggedIn ? go('/pages/mine/profile') : goLogin()">
        <view class="profile-main">
          <AppImage :width="160" class="avatar" :src="userStore.avatar || '/static/images/default-avatar.png'" mode="aspectFill" />
          <view class="profile-info" v-if="userStore.isLoggedIn">
            <text class="name">{{ userStore.nickname }}</text>
            <text class="phone" v-if="userStore.phone">{{ formatPhone(userStore.phone) }}</text>
            <text v-else class="phone">完善手机号，方便接收服务通知</text>
          </view>
          <view class="profile-info" v-else-if="userStore.isGuest">
            <text class="name">{{ userStore.guestInfo?.nickname || '游客' }}</text>
            <text class="phone">{{ userStore.guestInfo?.maskedPhone || '游客会话' }} · 点击登录正式账号</text>
          </view>
          <view class="profile-info" v-else>
            <text class="name">点击登录</text>
            <text class="phone">登录后查看订单和钱包</text>
          </view>
          <text class="profile-arrow">></text>
        </view>
      </view>

      <view class="service-panel">
        <view class="quick-item" @click="goOrders">
          <image class="quick-icon" src="/static/icons/订单.svg" mode="aspectFit" />
          <text>订单</text>
        </view>
        <view v-if="userStore.isLoggedIn" class="quick-item" @click="go('/pages/wallet/index')">
          <image class="quick-icon" src="/static/icons/钞票.svg" mode="aspectFit" />
          <text>钱包</text>
        </view>
        <view class="quick-item" @click="goAccount('/pages/coupons/index')">
          <image class="quick-icon" src="/static/icons/理财.svg" mode="aspectFit" />
          <text>优惠券</text>
        </view>
        <view v-if="userStore.isLoggedIn" class="quick-item" @click="goAccount('/pages/membership/index')">
          <image class="quick-icon" src="/static/icons/会员.svg" mode="aspectFit" />
          <text>会员等级</text>
        </view>
        <view class="quick-item" @click="go('/pages/display/index?tab=rank')">
          <image class="quick-icon" src="/static/icons/攻略.svg" mode="aspectFit" />
          <text>消费排行</text>
        </view>
        <view v-if="userStore.isLoggedIn" class="quick-item" @click="goAccount('/pages/invites/index')">
          <image class="quick-icon" src="/static/icons/分享.svg" mode="aspectFit" />
          <text>邀请好友</text>
        </view>
        <view v-if="userStore.isLoggedIn" class="quick-item" @click="go('/pages/message/index')">
          <view class="quick-icon-wrap">
            <image class="quick-icon" src="/static/icons/提示.svg" mode="aspectFit" />
            <view v-if="systemUnread > 0" class="quick-badge">{{ systemUnread > 99 ? '99+' : systemUnread }}</view>
          </view>
          <text>通知</text>
        </view>
        <view class="quick-item" @click="goCustomerService">
          <image class="quick-icon" src="/static/icons/客服.svg" mode="aspectFit" />
          <text>客服</text>
        </view>
      </view>

      <view class="more-card">
        <view class="more-heading">更多服务</view>
        <view v-if="userStore.isLoggedIn" class="player-row" @click="goPlayer">
          <image class="more-icon" src="/static/icons/攻略.svg" mode="aspectFit" />
          <view class="player-copy">
            <text class="more-title">{{ playerEntry.title }}</text>
            <text class="more-desc">{{ playerEntry.desc }}</text>
          </view>
          <text class="member-action">{{ playerEntry.action }}</text>
        </view>
        <view class="more-grid">
          <view v-if="identityAvailable && userStore.isLoggedIn" class="more-item" @click="go('/pages/mine/identity')">
            <image class="more-icon" src="/static/icons/安全认证.svg" mode="aspectFit" />
            <text>实名认证</text><text class="identity-state">{{ identityLabel }}</text>
          </view>
          <view v-if="userStore.isLoggedIn" class="more-item" @click="go('/pages/mine/library')">
            <image class="more-icon" src="/static/icons/暂无纪录.svg" mode="aspectFit" />
            <text>收藏与关注</text>
          </view>
          <view class="more-item" @click="go('/pages/complaint/list')">
            <image class="more-icon" src="/static/icons/警示.svg" mode="aspectFit" />
            <text>投诉工单</text>
          </view>
          <view v-if="siteStore.guestCheckoutEnabled" class="more-item" @click="go('/pages/order/lookup')">
            <image class="more-icon" src="/static/icons/订单.svg" mode="aspectFit" />
            <text>游客订单</text>
          </view>
          <view v-if="officialWechat.isEnabled('user')" class="more-item" @click="go('/pages/wechat/binding')">
            <image class="more-icon" src="/static/icons/提示.svg" mode="aspectFit" />
            <text>公众号通知</text>
          </view>
          <view v-if="userStore.isLoggedIn" class="more-item" @click="go('/pages/mine/privacy-center')">
            <image class="more-icon" src="/static/icons/安全认证.svg" mode="aspectFit" />
            <text>隐私与注销</text>
          </view>
        </view>
        <view class="legal-links">
          <text @click="go('/pages/agreement/user')">用户协议</text>
          <text class="legal-dot">·</text>
          <text @click="go('/pages/agreement/privacy')">隐私政策</text>
        </view>
      </view>

      <view v-if="userStore.isLoggedIn" class="logout" @click="userStore.logout()">退出登录</view>
      <view v-if="userStore.isGuest" class="logout guest-logout" @click="userStore.exitGuest()">退出游客会话</view>
      <TechSupportFooter />
      <view class="tab-page-bottom-spacer"></view>
    </scroll-view>
    <CustomTabBar :current="4" />
  </view>
</template>
<script setup>
import AppImage from '@/components/AppImage.vue'
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useUserStore } from '@/store/user'
import { useChatStore } from '@/store/chat'
import { useRemindStore } from '@/store/remind'
import { useAppStore } from '@/store/app'
import { useSiteStore } from '@/store/site'
import { getUserToken, setPlayerToken } from '@/utils/auth'
import { switchToPlayerToken } from '@/api/auth'
import { getPlayerApplicationStatus } from '@/api/player'
import { getIdentityStatus } from '@/api/user'
import { openCustomerService } from '@/utils/customer-service'
import { useOfficialWechatStore } from '@/store/official-wechat'
import CustomTabBar from '@/components/CustomTabBar.vue'
import TechSupportFooter from '@/components/TechSupportFooter.vue'
const userStore = useUserStore()
const siteStore = useSiteStore()
const appStore = useAppStore()
const chatStore = useChatStore()
const officialWechat = useOfficialWechatStore()
const remindStore = useRemindStore()
const systemUnread = computed(() => remindStore.systemUnread)
const identityAvailable=ref(false),identityStatus=ref('')
const playerStatus=ref('')
const identityLabel=computed(()=>({APPROVED:'已认证',PENDING:'审核中',REJECTED:'未通过',DRAFT:'待提交',NOT_SUBMITTED:'未认证'}[identityStatus.value]||'未认证'))
const playerEntry=computed(()=>{
  if(!userStore.isLoggedIn)return{title:'登录后入驻接单员端',desc:'登录后申请接单、查看收益和提现',action:'登录'}
  if(playerStatus.value==='ACTIVE')return{title:'接单员工作台',desc:'切换身份接单、查看收益和提现',action:'进入'}
  if(playerStatus.value==='PENDING')return{title:'接单员申请审核中',desc:'审核通过后即可进入接单员端',action:'查看'}
  if(playerStatus.value==='FROZEN')return{title:'接单员账号已冻结',desc:'请联系客服处理后再接单',action:'查看'}
  if(playerStatus.value==='REJECTED')return{title:'重新申请接单员',desc:'补充资料后可重新提交入驻申请',action:'申请'}
  return{title:'入驻接单员端',desc:'通过审核后可接单、查看收益和提现',action:'进入'}
})

// 每次显示页面时检查登录态并刷新用户信息
onShow(() => {
  userStore.syncSessionFromStorage()
  if (userStore.isLoggedIn) officialWechat.load('user')
  const savedToken = getUserToken()
  if (savedToken && !userStore.token) {
    userStore.token = savedToken
  }
  if (userStore.token) {
    userStore.fetchProfile(true)
    loadPlayerEntry()
    useChatStore().fetchMessageUnreadCount()
    getIdentityStatus({loading:false}).then(res=>{identityAvailable.value=!!res.data?.available;identityStatus.value=res.data?.status||''}).catch(()=>{identityAvailable.value=false})
  }
})

async function loadPlayerEntry(){
  try{
    const{data}=await getPlayerApplicationStatus()
    playerStatus.value=data?.status||''
  }catch(e){
    if(e?.data?.code===1002||e?.code===1002){playerStatus.value=''}
  }
}

const TAB_PAGES = ['/pages/index/index', '/pages/category/index', '/pages/players/index', '/pages/chat/list', '/pages/mine/index']
function go(url) {
  if (TAB_PAGES.includes(url)) {
    uni.switchTab({ url })
  } else {
    uni.navigateTo({ url })
  }
}
function goOrders() {
  if (userStore.isLoggedIn || userStore.isGuest) return go('/pages/order/list')
  if (siteStore.guestCheckoutEnabled) return go('/pages/order/lookup')
  uni.showToast({ title: '请先登录后查看订单', icon: 'none' })
}
function goLogin() { uni.navigateTo({ url: '/pages/login/index' }) }
function goAccount(url) { if (!userStore.checkLogin()) return; go(url) }
function formatPhone(p) {
  if (!p || p.length < 7) return p
  return p.slice(0, 3) + '****' + p.slice(-4)
}
async function goPlayer() {
  if (!userStore.checkLogin()) return
  try {
    const { data } = await getPlayerApplicationStatus()
    if (!data?.registered) {
      uni.navigateTo({ url: '/pages-player/apply/index' })
    } else if (data.status === 'PENDING') {
      uni.showToast({ title: '审核中，请耐心等待', icon: 'none' })
    } else if (data.status === 'ACTIVE') {
      const res = await switchToPlayerToken()
      if (res?.data?.token) {
        setPlayerToken(res.data.token)
        useChatStore().disconnect()
        appStore.switchToPlayer()
      }
    } else if (data.status === 'FROZEN') {
      uni.showToast({ title: '接单账号已冻结，请联系客服', icon: 'none' })
    } else if (data.status === 'REJECTED') {
      uni.navigateTo({ url: '/pages-player/apply/index' })
    } else {
      uni.showToast({ title: '接单账号状态异常，请联系客服', icon: 'none' })
    }
  } catch (e) {
    const msg = e?.data?.msg || e?.message
    if (msg) uni.showToast({ title: msg, icon: 'none' })
    if (e?.data?.code === 1002) uni.navigateTo({ url: '/pages-player/apply/index' })
  }
}
async function goCustomerService() {
  try {
    await openCustomerService()
  } catch (e) {
    uni.showToast({ title: e?.msg || '连接客服失败', icon: 'none' })
  }
}
</script>
<style lang="scss" scoped>
.mine-page {
  background: #f7f8f9;
  height: var(--app-page-height, 100vh);
  position: relative;
  overflow: hidden;
  padding-bottom: 0;
}
.mine-scroll {
  height: calc(var(--app-page-height, 100vh) - 110rpx - var(--app-safe-bottom, env(safe-area-inset-bottom)));
  position: relative;
  z-index: 1;
  overflow: hidden;
}

/* #ifdef H5 */
.mine-scroll :deep(.uni-scroll-view::-webkit-scrollbar),
.mine-scroll :deep(.uni-scroll-view-content::-webkit-scrollbar) {
  width: 0;
  height: 0;
  display: none;
}
/* #endif */

.profile-card {
  margin: 24rpx 24rpx 0;
  padding: 24rpx;
  background: linear-gradient(135deg, #ffffff, #f8f8f9);
  border: 1rpx solid rgba(23, 25, 28, 0.14);
  border-radius: 20rpx;
  box-shadow: 0 12rpx 34rpx rgba(23, 25, 28, 0.04);
  position: relative;
  z-index: 1;
}
.profile-main {
  display: flex;
  align-items: center;
}
.avatar {
  width: 104rpx;
  height: 104rpx;
  border-radius: 50%;
  background: #ffffff;
  border: 4rpx solid rgba(23, 25, 28, 0.14);
  margin-right: 20rpx;
  flex-shrink: 0;
}
.profile-info {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}
.name {
  display: block;
  font-size: 32rpx;
  font-weight: 800;
  color: #17191c;
  line-height: 1.25;
}
.phone {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #73777e;
  line-height: 1.35;
}
.profile-arrow {
  color: #b9bdc4;
  font-size: 32rpx;
  flex-shrink: 0;
  margin-left: 12rpx;
}

.service-panel {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 18rpx 8rpx;
  margin: 18rpx 24rpx 0;
  padding: 20rpx 8rpx 16rpx;
  background: #ffffff;
  border-radius: 20rpx;
  box-shadow: 0 10rpx 30rpx rgba(23, 25, 28, 0.04);
  position: relative;
  z-index: 1;
}
.quick-item {
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  font-size: 22rpx;
  color: #41464e;
  font-weight: 600;
}
.quick-icon-wrap {
  position: relative;
}
.quick-icon {
  width: 48rpx;
  height: 48rpx;
}
.quick-badge {
  position: absolute;
  top: -12rpx;
  right: -20rpx;
  min-width: 32rpx;
  height: 32rpx;
  line-height: 32rpx;
  padding: 0 8rpx;
  font-size: 20rpx;
  color: #fff;
  background: #d44955;
  border-radius: 32rpx;
  text-align: center;
}

.more-card {
  margin: 18rpx 24rpx 0;
  background: #ffffff;
  border-radius: 20rpx;
  box-shadow: 0 10rpx 30rpx rgba(23, 25, 28, 0.04);
  overflow: hidden;
  position: relative;
  z-index: 1;
}
.more-heading {
  display: block;
  padding: 20rpx 22rpx 12rpx;
  font-size: 23rpx;
  color: #858a92;
  font-weight: 700;
}
.player-row {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 18rpx 22rpx;
  border-bottom: 1rpx solid #f4f5f6;
  background: #fafbfc;
  &:active { background: #f4f6f8; }
}
.more-icon { width: 38rpx; height: 38rpx; flex-shrink: 0; filter: grayscale(1); opacity: 0.72; }
.player-copy { flex: 1; min-width: 0; display: grid; gap: 4rpx; }
.more-title { font-size: 27rpx; font-weight: 700; color: #17191c; }
.more-desc { font-size: 21rpx; color: #858a92; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.member-action { flex-shrink: 0; padding: 7rpx 18rpx; border-radius: 999rpx; background: #17191c; color: #fff; font-size: 22rpx; font-weight: 700; }
.more-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.more-item {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 18rpx 20rpx;
  border-bottom: 1rpx solid #f4f5f6;
  font-size: 24rpx;
  color: #41464e;
  &:nth-child(odd) { border-right: 1rpx solid #f4f5f6; }
  &:active { background: #fafbfc; }
  > text:first-of-type { flex: 1; min-width: 0; }
}
.identity-state { flex: none; font-size: 20rpx; color: #858a92; }
.legal-links { display: flex; justify-content: center; align-items: center; gap: 10rpx; padding: 20rpx; font-size: 21rpx; color: #9a9fa7; }
.legal-dot { color: #c4c7cc; }
.logout {
  margin: 40rpx 24rpx;
  height: 88rpx;
  line-height: 88rpx;
  text-align: center;
  background: #ffffff;
  color: #c94f59;
  font-size: 30rpx;
  border-radius: 16rpx;
  box-shadow: 0 4rpx 20rpx rgba(201, 79, 89, 0.08);
  position: relative;
  z-index: 1;
}
.guest-logout { color: #555b63; }

</style>
