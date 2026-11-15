<template>
  <view class="screen-header" :class="{ 'with-border': border, centered: center }">
    <view class="screen-header__left">
      <view v-if="back" class="screen-back" role="button" aria-label="返回" @click="goBack">
        <image class="back-icon" src="/static/icons/header-back.svg" mode="aspectFit" />
      </view>
      <view class="screen-heading">
        <text class="screen-title">{{ title }}</text>
        <text v-if="subtitle" class="screen-subtitle">{{ subtitle }}</text>
      </view>
    </view>
    <view class="screen-actions">
      <slot />
    </view>
  </view>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  back: { type: Boolean, default: false },
  border: { type: Boolean, default: false },
  center: { type: Boolean, default: false }
})

function goBack() {
  const pages = getCurrentPages()
  if (pages.length > 1) uni.navigateBack()
  else uni.switchTab({ url: '/pages/index/index' })
}
</script>

<style lang="scss" scoped>
.screen-header {
  position: relative;
  z-index: 8;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18rpx;
  min-height: calc(92rpx + env(safe-area-inset-top));
  padding: calc(10rpx + env(safe-area-inset-top)) 28rpx 8rpx;
  box-sizing: border-box;
  background: transparent;
}
.screen-header.with-border {
  border-bottom: 1rpx solid #eeeeee;
  background: #ffffff;
}
.screen-header__left {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 12rpx;
}
.screen-heading {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.screen-title {
  display: block;
  color: #111111;
  font-size: 40rpx;
  font-weight: 850;
  line-height: 1.15;
  letter-spacing: -0.03em;
}
.screen-header.centered .screen-heading {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  align-items: center;
  text-align: center;
  max-width: 60%;
}
.screen-header.centered .screen-header__left {
  min-width: 44rpx;
}
.screen-header.centered .screen-actions {
  margin-left: auto;
}
.screen-subtitle {
  display: block;
  margin-top: 5rpx;
  color: #aaaaaa;
  font-size: 22rpx;
  line-height: 1.3;
}
.screen-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6rpx;
  flex-shrink: 0;
}
.screen-back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56rpx;
  height: 56rpx;
  border-radius: 50%;
  color: #111111;
  background: #ffffff;
  box-shadow: 0 4rpx 16rpx rgba(17, 19, 22, 0.04);
}
.screen-back .back-icon {
  display: block;
  width: 32rpx;
  height: 32rpx;
}
.screen-header.centered .screen-title {
  font-size: 40rpx;
}
</style>
