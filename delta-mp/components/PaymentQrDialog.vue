<template>
  <view v-if="visible" class="qr-overlay" @touchmove.stop.prevent>
    <view class="qr-panel">
      <view class="qr-header">
        <text class="qr-title">{{ title || '微信扫码支付' }}</text>
        <text class="qr-close" @click="close">×</text>
      </view>
      <view class="qr-stage">
        <image v-if="qrImage" class="qr-image" :src="qrImage" mode="widthFix" />
        <view v-else class="qr-placeholder">{{ qrError || '二维码生成中…' }}</view>
      </view>
      <text class="qr-tip">请使用微信扫一扫完成支付</text>
      <text class="qr-hint">{{ hint || '支付完成后请返回本页面刷新状态' }}</text>
      <view class="qr-actions">
        <view class="qr-button secondary" @click="copyLink">复制支付链接</view>
        <view class="qr-button primary" @click="close">关闭</view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, watch } from 'vue'
import QRCode from 'qrcode'

const props = defineProps({
  visible: { type: Boolean, default: false },
  url: { type: String, default: '' },
  title: { type: String, default: '微信扫码支付' },
  hint: { type: String, default: '' }
})
const emit = defineEmits(['update:visible', 'closed'])
const qrImage = ref('')
const qrError = ref('')
let generation = 0

watch(() => [props.visible, props.url], async ([visible, url]) => {
  const current = ++generation
  qrImage.value = ''
  qrError.value = ''
  if (!visible || !url) return
  try {
    const image = await QRCode.toDataURL(url, {
      width: 560,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: '#17191cff', light: '#ffffffff' }
    })
    if (current === generation) qrImage.value = image
  } catch (_) {
    if (current === generation) qrError.value = '二维码生成失败，请使用下方按钮复制链接'
  }
}, { immediate: true })

function copyLink() {
  if (!props.url) return
  uni.setClipboardData({ data: props.url })
}

function close() {
  emit('update:visible', false)
  emit('closed')
}
</script>

<style lang="scss" scoped>
.qr-overlay{position:fixed;inset:0;z-index:1200;display:flex;align-items:center;justify-content:center;padding:32rpx;background:rgba(17,19,22,.58);box-sizing:border-box}
.qr-panel{width:100%;max-width:640rpx;padding:30rpx;border-radius:28rpx;background:#fff;box-sizing:border-box;box-shadow:0 24rpx 80rpx rgba(0,0,0,.2)}
.qr-header{display:flex;align-items:center;justify-content:space-between;gap:20rpx}
.qr-title{font-size:32rpx;font-weight:800;color:#17191c}
.qr-close{width:56rpx;height:56rpx;line-height:52rpx;text-align:center;font-size:42rpx;color:#73777e}
.qr-stage{display:flex;align-items:center;justify-content:center;margin:24rpx auto 18rpx;padding:18rpx;border:1rpx solid #eceef1;border-radius:20rpx;background:#fff}
.qr-image{width:480rpx;height:480rpx}
.qr-placeholder{display:flex;align-items:center;justify-content:center;width:480rpx;height:480rpx;color:#858992;font-size:24rpx;text-align:center;line-height:1.6}
.qr-tip,.qr-hint{display:block;text-align:center}
.qr-tip{font-size:27rpx;font-weight:700;color:#17191c}
.qr-hint{margin-top:10rpx;font-size:22rpx;color:#858992}
.qr-actions{display:flex;gap:16rpx;margin-top:26rpx}
.qr-button{flex:1;height:78rpx;line-height:78rpx;text-align:center;border-radius:999rpx;font-size:26rpx;font-weight:700;&.secondary{background:#f1f2f4;color:#343a43}&.primary{background:#17191c;color:#fff}}
</style>
