<template>
  <view class="gold-dust-wrap">
    <canvas type="2d" :id="canvasId" class="gold-canvas" />
  </view>
</template>

<script setup>
import { useGoldDust } from '@/composables/useGoldDust'
const canvasId = 'gd_' + Math.random().toString(36).slice(2, 8)
useGoldDust(canvasId)
</script>

<style lang="scss" scoped>
.gold-dust-wrap {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 2;
}
.gold-canvas {
  width: 100%;
  height: 100%;
}
</style>
