<template>
  <view class="membership-page">
    <PageState v-if="loading || error" :loading="loading" :phase="loaded ? 'refresh' : 'initial'" :error="error" @retry="load" />
    <view v-if="loaded && !data.enabled" class="empty">会员等级功能暂未开启</view>
    <template v-else-if="loaded">
      <view class="hero">
        <text class="eyebrow">MEMBERSHIP</text>
        <text class="tier">{{ data.tier?.name || '普通会员' }}</text>
        <text class="spend">有效净消费 ¥{{ Number(data.netSpend || 0).toFixed(2) }}</text>
      </view>
      <view class="card">
        <view class="row">
          <text class="label">当前折扣</text>
          <text class="value">{{ discountText(data.tier?.discountRate) }}</text>
        </view>
        <view class="row">
          <text class="label">下一等级</text>
          <text class="value">{{ data.nextTier?.name || '已达最高等级' }}</text>
        </view>
        <view v-if="data.nextTier" class="progress-track">
          <view class="progress-fill" :style="{ width: progress + '%' }" />
        </view>
        <text v-if="data.nextTier" class="progress-text">再消费 ¥{{ remaining.toFixed(2) }} 升级 {{ data.nextTier.name }}</text>
      </view>
      <view class="hint">等级按有效业务消费计算，充值、押金、未支付订单和已退款金额不计入。会员折扣接入订单结算后会在此页面同步展示。</view>
    </template>
  </view>
</template>

<script setup>
import PageState from '@/components/PageState.vue'
import { computed, onMounted, ref } from 'vue'
import { getMembership } from '@/api/membership'

const loading = ref(true)
const loaded = ref(false)
const error = ref('')
const data = ref({ enabled: false })
const remaining = computed(() => Math.max(0, Number(data.value.nextThreshold || 0) - Number(data.value.netSpend || 0)))
const progress = computed(() => {
  const spend = Number(data.value.netSpend || 0)
  const target = Number(data.value.nextThreshold || 0)
  const current = Number(data.value.tier?.minSpend || 0)
  if (!target || target <= current) return 100
  return Math.max(0, Math.min(100, ((spend - current) / (target - current)) * 100))
})

function discountText(rate) {
  const value = Number(rate)
  if (!Number.isFinite(value) || value >= 1) return '无折扣'
  return (value * 10).toFixed(1).replace(/\.0$/, '') + ' 折'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await getMembership()
    data.value = response.data || { enabled: false }
    loaded.value = true
  } catch (e) {
    error.value = e?.msg || e?.message || '暂时无法加载，请重试'
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<style lang="scss" scoped>
.membership-page { min-height: var(--app-page-height, 100vh); padding: 28rpx; background: var(--store-soft); box-sizing: border-box; }
.hero { padding: 44rpx 32rpx; border-radius: 24rpx; background: var(--store-primary); color: #fff; }
.eyebrow { font-size: 20rpx; letter-spacing: 3rpx; opacity: .65; }
.tier { display: block; margin-top: 18rpx; font-size: 44rpx; font-weight: 700; }
.spend { display: block; margin-top: 12rpx; font-size: 25rpx; opacity: .72; }
.card { margin-top: 22rpx; padding: 28rpx; border-radius: 20rpx; background: var(--store-surface); }
.row { display: flex; justify-content: space-between; padding: 16rpx 0; font-size: 26rpx; color: var(--store-text); }
.label { color: var(--store-regular); }
.value { font-weight: 600; }
.progress-track { height: 12rpx; margin-top: 20rpx; border-radius: 12rpx; background: var(--store-soft); overflow: hidden; }
.progress-fill { height: 100%; border-radius: 12rpx; background: var(--store-primary); }
.progress-text { display: block; margin-top: 12rpx; font-size: 23rpx; color: var(--store-regular); }
.hint { margin-top: 24rpx; padding: 22rpx; font-size: 23rpx; line-height: 1.7; color: var(--store-regular); }
.empty { padding: 180rpx 0; text-align: center; color: var(--store-regular); font-size: 25rpx; }
</style>
