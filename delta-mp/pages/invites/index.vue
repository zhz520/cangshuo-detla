<template>
  <view class="invite-page">
    <view class="hero">
      <text class="eyebrow">INVITE</text>
      <text class="title">邀请好友</text>
      <text class="hint">好友首单完成后，奖励自动进入你的钱包。</text>
    </view>
    <view class="card">
      <view class="row"><text class="label">我的邀请码</text><text class="code">{{ info.code || '生成中…' }}</text></view>
      <view class="row"><text class="label">已邀请</text><text class="value">{{ info.invitedCount || 0 }} 人</text></view>
      <view class="row"><text class="label">累计奖励</text><text class="value">¥{{ Number(info.rewardTotal || 0).toFixed(2) }}</text></view>
      <button class="copy" @click="copyInviteLink">复制邀请链接</button>
    </view>
    <view class="card">
      <text class="card-title">填写好友邀请码</text>
      <input v-model.trim="inputCode" class="input" maxlength="8" placeholder="请输入 8 位邀请码" />
      <button class="bind" :disabled="binding" @click="bind">{{ binding ? '绑定中…' : '绑定邀请关系' }}</button>
      <text class="small">每个账号只能归因一次，绑定后不能更换邀请人。</text>
    </view>
    <view v-if="relations.length" class="card">
      <text class="card-title">邀请记录</text>
      <view v-for="(item, index) in relations" :key="index" class="relation">
        <text>{{ statusText(item.status) }}</text>
        <text class="time">{{ formatTime(item.created_at) }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { bindInviteCode, getInviteInfo } from '@/api/invite'
import { useSiteStore } from '@/store/site'

const info = ref({})
const relations = ref([])
const inputCode = ref('')
const binding = ref(false)
const siteStore = useSiteStore()
const inviteLink = computed(() => {
  if (!info.value.code) return ''
  let origin = String(siteStore.customerWebUrl || '').replace(/\/$/, '')
  // #ifdef H5
  if (!origin && typeof window !== 'undefined') origin = window.location.origin
  // #endif
  return origin ? `${origin}/?invite=${encodeURIComponent(info.value.code)}` : info.value.code
})

function statusText(status) {
    return ({ PENDING: '等待首单', REWARDED: '已发放奖励', REFUNDED: '首单已退款，奖励已冲正' })[status] || status
}
function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return date.toLocaleDateString('zh-CN')
}
async function load() {
  try {
    const { data } = await getInviteInfo()
    info.value = data || {}
    relations.value = Array.isArray(data?.relations) ? data.relations : []
  } catch (_) {}
}
function copyInviteLink() {
  if (!inviteLink.value) return
  uni.setClipboardData({ data: inviteLink.value, success: () => uni.showToast({ title: '邀请链接已复制', icon: 'none' }) })
}
async function bind() {
  if (binding.value) return
  if (!/^[A-Z2-9]{8}$/.test(inputCode.value.toUpperCase())) return uni.showToast({ title: '请输入 8 位邀请码', icon: 'none' })
  binding.value = true
  try {
    await bindInviteCode(inputCode.value.toUpperCase())
    uni.showToast({ title: '绑定成功', icon: 'none' })
    inputCode.value = ''
    await load()
  } catch (_) {
  } finally {
    binding.value = false
  }
}
onMounted(load)
</script>

<style lang="scss" scoped>
.invite-page { min-height: var(--app-page-height, 100vh); padding: 28rpx; background: #f7f8f9; box-sizing: border-box; }
.hero { padding: 42rpx 30rpx; border-radius: 24rpx; background: #17191c; color: #fff; }
.eyebrow { font-size: 20rpx; letter-spacing: 3rpx; opacity: .6; }
.title { display: block; margin-top: 14rpx; font-size: 42rpx; font-weight: 700; }
.hint { display: block; margin-top: 12rpx; font-size: 24rpx; opacity: .7; line-height: 1.6; }
.card { margin-top: 20rpx; padding: 28rpx; border-radius: 20rpx; background: #fff; }
.row { display: flex; align-items: center; justify-content: space-between; padding: 16rpx 0; }
.label { color: #73777e; font-size: 25rpx; }
.code { font-size: 34rpx; font-weight: 700; letter-spacing: 4rpx; color: #17191c; }
.value { font-size: 27rpx; font-weight: 600; color: #17191c; }
.copy, .bind { margin-top: 16rpx; height: 72rpx; line-height: 72rpx; border-radius: 36rpx; background: #17191c; color: #fff; font-size: 25rpx; }
.card-title { display: block; font-size: 28rpx; font-weight: 700; color: #17191c; margin-bottom: 18rpx; }
.input { height: 78rpx; padding: 0 22rpx; border-radius: 12rpx; background: #f2f3f5; font-size: 26rpx; }
.small { display: block; margin-top: 12rpx; font-size: 22rpx; color: #73777e; line-height: 1.6; }
.relation { display: flex; justify-content: space-between; padding: 18rpx 0; border-top: 1rpx solid #f0f1f2; font-size: 24rpx; color: #555b63; }
.time { color: #73777e; }
</style>
