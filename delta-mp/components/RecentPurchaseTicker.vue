<template>
  <view v-if="items.length && !hidden" class="purchase-ticker" role="status" aria-label="最近购买动态">
    <text class="ticker-tag">热购</text>
    <swiper
      class="ticker-swiper"
      vertical
      :autoplay="items.length > 1"
      :circular="items.length > 1"
      :interval="3200"
      :duration="420"
      :disable-touch="true"
    >
      <swiper-item v-for="(item, index) in items" :key="`${item.nickname}-${item.product_name}-${index}`">
        <view class="ticker-line" @click="$emit('more')">
          <AppImage class="ticker-avatar" :src="item.avatar || ''" :width="80" mode="aspectFill" />
          <view class="ticker-copy">
            <text class="ticker-name">{{ item.nickname || '匿名玩家' }}</text>
            <text class="ticker-muted"> 购买了 </text>
            <text class="ticker-product">{{ item.product_name || '服务商品' }}</text>
          </view>
        </view>
      </swiper-item>
    </swiper>
    <text class="ticker-more" @click.stop="$emit('more')">更多</text>
    <text class="ticker-close" aria-label="关闭最近购买动态" @click.stop="hidden = true">×</text>
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
  box-shadow: 0 12rpx 30rpx rgba(23, 25, 28, 0.22);
  backdrop-filter: none;
}

.ticker-tag {
  flex-shrink: 0;
  padding: 4rpx 10rpx;
  border-radius: 999rpx;
  font-size: 20rpx;
  line-height: 1.2;
  color: #ffe1b8;
  background: rgba(255, 193, 112, 0.16);
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
.ticker-product { color: #ffe1b8; font-weight: 600; }

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
