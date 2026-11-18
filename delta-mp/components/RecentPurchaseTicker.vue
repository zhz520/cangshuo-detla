<template>
  <view v-if="items.length && !hidden" class="purchase-ticker" role="status" aria-label="最近购买动态">
    <swiper
      class="ticker-swiper"
      vertical
      :autoplay="items.length > 1 && !reducedMotion"
      :circular="items.length > 1 && !reducedMotion"
      :interval="3200"
      :duration="160"
      :disable-touch="true"
    >
      <swiper-item v-for="(item, index) in items" :key="`${item.nickname}-${item.product_name}-${index}`">
        <view class="ticker-line" @click="$emit('more')">
          <AppImage class="ticker-avatar" src="/static/images/delta-default-avatar.png" :width="80" mode="aspectFill" />
          <view class="ticker-copy">
            <text class="ticker-name">{{ item.nickname || '匿名玩家' }}</text>
            <text class="ticker-muted"> 购买了 </text>
            <text class="ticker-product">{{ item.product_name || '服务商品' }}</text>
          </view>
        </view>
      </swiper-item>
    </swiper>
    <text class="ticker-more" role="button" aria-label="查看最近购买" @click.stop="$emit('more')">›</text>
    <text class="ticker-close" role="button" aria-label="关闭最近购买动态" @click.stop="hidden = true">×</text>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import AppImage from '@/components/AppImage.vue'

defineProps({
  items: { type: Array, default: () => [] }
})
defineEmits(['more'])

const hidden = ref(false)
const reducedMotion = ref(false)
// #ifdef H5
reducedMotion.value = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
// #endif
</script>

<style scoped>
.purchase-ticker {
  position: fixed;
  left: calc(var(--app-content-left, 0px) + 24rpx);
  right: calc(var(--app-content-left, 0px) + 136rpx);
  bottom: calc(126rpx + var(--app-safe-bottom, 0px));
  z-index: 98;
  height: 72rpx;
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 0 16rpx 0 14rpx;
  box-sizing: border-box;
  border-radius: 999rpx;
  color: #fff;
  background: rgba(23, 25, 28, 0.94);
  box-shadow: 0 4rpx 18rpx rgba(23, 25, 28, 0.12);
  backdrop-filter: none;
}

.ticker-swiper {
  flex: 1;
  min-width: 0;
  height: 72rpx;
}

.ticker-line {
  height: 72rpx;
  display: flex;
  align-items: center;
  gap: 10rpx;
  min-width: 0;
}

.ticker-avatar {
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.14);
}

.ticker-copy {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 22rpx;
  line-height: 72rpx;
}

.ticker-name { color: #fff; font-weight: 700; }
.ticker-muted { color: rgba(255, 255, 255, 0.64); }
.ticker-product { color: #fff; font-weight: 600; }

.ticker-more,
.ticker-close {
  flex-shrink: 0;
  color: rgba(255, 255, 255, 0.7);
}

.ticker-more { font-size: 20rpx; }
.ticker-close { padding-left: 2rpx; font-size: 30rpx; line-height: 1; }

@media (max-width: 360px) {
  .purchase-ticker { right: calc(var(--app-content-left, 0px) + 124rpx); }
  .ticker-copy { font-size: 20rpx; }
}
</style>
