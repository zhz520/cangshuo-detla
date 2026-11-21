<template>
  <view class="complaint-page">
    <!-- 关联订单信息 -->
    <view class="order-card" v-if="orderInfo">
      <text class="card-title">📦 关联订单</text>
      <view class="info-row"><text class="label">订单号</text><text class="value">{{ orderInfo.orderNo }}</text></view>
      <view class="info-row"><text class="label">商品</text><text class="value">{{ orderInfo.productName }}</text></view>
      <view class="info-row"><text class="label">金额</text><text class="value price">¥{{ orderInfo.amount }}</text></view>
    </view>
    <view class="form-item"><text class="label">投诉类型</text>
      <picker :range="types" @change="typeIdx=$event.detail.value"><view class="picker">{{ types[typeIdx] || '请选择' }}</view></picker>
    </view>
    <view class="form-item"><text class="label">投诉内容</text>
      <textarea v-model="content" placeholder="请描述您遇到的问题" :maxlength="500" class="content-input" />
    </view>
    <view class="form-item"><text class="label">证据附件（选填）</text>
      <ComplaintAttachmentUploader v-model="attachmentIds" :order-id="orderId" :max="6" @uploading="attachmentUploading = $event" />
    </view>
    <view class="form-item"><text class="label">期望结果</text>
      <picker :range="expectedOptions" @change="expectedResult=expectedOptions[$event.detail.value]"><view class="picker">{{ expectedResult || '请选择' }}</view></picker>
    </view>
    <view class="btn" :class="{ disabled: submitting }" @click="submit">提交投诉</view>
  </view>
</template>
<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import ComplaintAttachmentUploader from '@/components/ComplaintAttachmentUploader.vue'
import { createComplaint } from '@/api/complaint'
import { getOrderDetail } from '@/api/order'

const orderId = ref(0)
const orderInfo = ref(null)
const types = ['服务态度', '服务质量', '违规操作', '其他']
const expectedOptions = ['退款', '重新服务', '赔偿', '道歉']
const typeIdx = ref(0)
const content = ref('')
const attachmentIds = ref([])
const attachmentUploading = ref(false)
const expectedResult = ref('')
const submitting = ref(false)

onLoad(async (opts) => {
  orderId.value = Number(opts.orderId)
  if (orderId.value) {
    try {
      const res = await getOrderDetail(orderId.value)
      orderInfo.value = res.data
    } catch (e) { /* ignore */ }
  }
})

async function submit() {
  if (submitting.value) return
  if (!orderId.value) return uni.showToast({ title: '订单参数异常', icon: 'none' })
  if (attachmentUploading.value) return uni.showToast({ title: '附件上传中，请稍候', icon: 'none' })
  if (!content.value) return uni.showToast({ title: '请描述投诉内容', icon: 'none' })
  submitting.value = true
  try {
    await createComplaint({
      orderId: orderId.value,
      type: types[typeIdx.value],
      content: content.value,
      attachmentIds: [...attachmentIds.value],
      expectedResult: expectedResult.value || null
    })
    uni.showToast({ title: '已提交' })
    setTimeout(() => uni.navigateBack(), 1500)
  } catch (e) {
    uni.showToast({ title: e?.data?.msg || '提交失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}
</script>
<style lang="scss" scoped>
.complaint-page { padding: 24rpx; background: var(--store-soft); min-height: var(--app-page-height, 100vh); }
.order-card { background: var(--store-surface); border: 1rpx solid var(--store-line); padding: 24rpx; border-radius: 12rpx; margin-bottom: 24rpx; }
.card-title { font-size: 28rpx; font-weight: bold; color: var(--store-text); display: block; margin-bottom: 16rpx; }
.info-row { display: flex; align-items: center; padding: 6rpx 0; .label { font-size: 26rpx; color: var(--store-regular); width: 100rpx; } .value { font-size: 26rpx; color: var(--store-text); flex: 1; } .price { color: var(--store-text); font-weight: bold; } }
.form-item { background: var(--store-surface); border: 1rpx solid var(--store-line); padding: 24rpx; border-radius: 16rpx; margin-bottom: 20rpx; .label { font-size: 26rpx; color: var(--store-regular); margin-bottom: 12rpx; display: block; } .picker { height: 60rpx; line-height: 60rpx; background: var(--store-soft); padding: 0 24rpx; border-radius: 8rpx; color: var(--store-text); } }
.content-input { width: 100%; height: 200rpx; background: var(--store-soft); border: none; border-radius: 8rpx; padding: 20rpx; font-size: 28rpx; color: var(--store-text); }
.btn { height: 88rpx; line-height: 88rpx; text-align: center; background: var(--store-primary); color: #ffffff; font-weight: bold; font-size: 32rpx; border-radius: 999rpx; margin-top: 40rpx; &.disabled { opacity: 0.6; } }
</style>
