<template>
  <view class="staff-transfer">
    <view class="brand-card">
      <AppImage v-if="siteStore.logo" class="logo" :src="siteStore.logo" mode="aspectFit" />
      <view v-else class="logo placeholder">工</view>
      <text class="title">{{ siteStore.siteName }}</text>
      <text class="subtitle">工作人员请使用后台域名</text>
    </view>
    <view class="transfer-card">
      <text class="status">{{ loading ? '正在前往工作人员后台…' : configured ? '客户页面不提供工作人员登录' : '尚未配置工作人员后台地址' }}</text>
      <text class="tip">管理员和客服统一从后台域名登录，客户 H5 不再保存后台账号登录状态。</text>
      <button v-if="configured" class="submit" :disabled="loading" @click="openBackend">进入工作人员后台</button>
      <text v-else class="diagnostic">请联系管理员在“系统配置 → 站点配置”中填写“工作人员后台地址”。</text>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AppImage from '@/components/AppImage.vue'
import { useSiteStore } from '@/store/site'
import { staffLoginUrl } from '@/utils/staff-entry'
import { removeCsToken } from '@/utils/auth'

const siteStore = useSiteStore()
const loading = ref(false)
const configured = computed(() => Boolean(staffLoginUrl(siteStore.staffWebUrl)))

async function openBackend() {
  const target = staffLoginUrl(siteStore.staffWebUrl)
  if (!target) return
  // #ifdef H5
  if (new URL(target).origin === window.location.origin) {
    uni.showToast({ title: '工作人员后台地址配置错误，不能与客户域名相同', icon: 'none' })
    return
  }
  window.location.replace(target + '?from=customer-h5')
  // #endif
  // #ifndef H5
  uni.setClipboardData({ data: target, success: () => uni.showToast({ title: '后台地址已复制，请在浏览器打开', icon: 'none' }) })
  // #endif
}

onLoad(async () => {
  removeCsToken()
  loading.value = true
  try {
    await siteStore.fetchSiteConfig()
    if (configured.value) await openBackend()
  } finally {
    loading.value = false
  }
})
</script>

<style lang="scss" scoped>
.staff-transfer { min-height: var(--app-page-height, 100vh); padding: 36rpx 28rpx calc(48rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); box-sizing: border-box; background: #f4f5f6; }
.brand-card, .transfer-card { width: 100%; max-width: 720rpx; margin: 0 auto; box-sizing: border-box; background: #fff; border: 1rpx solid #eceef1; border-radius: 24rpx; }
.brand-card { padding: 44rpx 28rpx; display: flex; flex-direction: column; align-items: center; }
.logo { width: 112rpx; height: 112rpx; border-radius: 24rpx; background: var(--store-primary); }
.logo.placeholder { display: flex; align-items: center; justify-content: center; color: #fff; font-size: 44rpx; font-weight: 700; }
.title { margin-top: 20rpx; font-size: 36rpx; line-height: 1.3; font-weight: 800; color: var(--store-text); }
.subtitle { margin-top: 8rpx; font-size: 24rpx; color: var(--store-muted); }
.transfer-card { margin-top: 20rpx; padding: 34rpx 28rpx; }
.status { display: block; color: var(--store-text); font-size: 30rpx; font-weight: 700; line-height: 1.5; }
.tip, .diagnostic { display: block; margin-top: 18rpx; color: var(--store-muted); font-size: 24rpx; line-height: 1.8; }
.submit { margin-top: 30rpx; height: 88rpx; line-height: 88rpx; border: 0; border-radius: 999rpx; background: var(--store-primary); color: #fff; font-size: 29rpx; font-weight: 700; }
.submit[disabled] { opacity: .55; }
</style>
