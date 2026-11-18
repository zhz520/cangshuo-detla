<template><view class="maintenance"><view class="card"><text class="title">服务暂时停止</text><text class="description">项目正在维护或维护期已到期，请联系平台管理员。恢复后可重新进入。</text><button :loading="checking" @click="check">检查是否恢复</button></view></view></template>
<script setup>
import {ref} from 'vue'
import {request} from '@/api/request'
const checking=ref(false)
async function check(){checking.value=true;try{const {data}=await request({url:'/system/maintenance/status',auth:false,loading:false});if(!data.suspended&&!data.updating)uni.reLaunch({url:'/pages/index/index'});else uni.showToast({title:'服务暂未恢复，请稍后再试',icon:'none'})}finally{checking.value=false}}
</script>
<style scoped>.maintenance{min-height:100vh;display:flex;align-items:center;justify-content:center;padding:40rpx;background:var(--store-soft)}.card{display:flex;flex-direction:column;gap:36rpx;text-align:center;padding:50rpx;background:var(--store-surface);border-radius:24rpx}.title{font-size:42rpx;font-weight:bold}.description{color:var(--store-regular);line-height:1.8;font-size:28rpx}button{background:var(--store-primary);color:white;width:100%;border-radius:16rpx;font-size:28rpx}</style>
