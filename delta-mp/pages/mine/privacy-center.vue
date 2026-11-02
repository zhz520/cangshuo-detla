<template><view class="privacy-center"><view class="card"><text class="heading">隐私与账号</text><text class="tip">可申请删除打手实名资料或注销账号。注销前需处理完订单、余额、提现和售后；审核通过后清除个人资料并退出所有登录，保留必要的交易审计记录。</text><textarea v-model="note" maxlength="500" placeholder="申请说明（选填）"/><button :disabled="busy" @click="submit('ERASE_IDENTITY')">申请删除实名资料</button><button class="danger" :disabled="busy" @click="submit('CLOSE_ACCOUNT')">申请注销账号</button></view><view class="card"><text class="heading">申请记录</text><view v-for="row in rows" :key="row.id" class="record"><text>{{row.kind==='CLOSE_ACCOUNT'?'注销账号':'删除实名资料'}} · {{statuses[row.status]||row.status}}</text><text class="tip">{{row.note||'等待平台处理'}}</text></view><text v-if="!rows.length" class="tip">暂无申请</text></view></view></template>
<script setup>
import {ref} from 'vue'
import {onShow} from '@dcloudio/uni-app'
import {get,post} from '@/api/request'
const rows=ref([]),note=ref(''),busy=ref(false),statuses={PENDING:'待处理',APPROVED:'已批准',REJECTED:'已驳回'}
async function load(){try{rows.value=(await get('/user/privacy/requests',{}, {role:'user'})).data}catch(_){}}
async function submit(kind){if(busy.value)return;const result=await uni.showModal({title:kind==='CLOSE_ACCOUNT'?'申请注销账号':'删除实名资料',content:'确认提交申请？平台将核对身份及未完成业务后处理。'});if(!result.confirm)return;busy.value=true;try{await post('/user/privacy/requests',{kind,note:note.value},{role:'user'});note.value='';uni.showToast({title:'申请已提交',icon:'none'});await load()}catch(_){}finally{busy.value=false}}
onShow(load)
</script>
<style scoped>.privacy-center{padding:24rpx;background:#f7f8f9;min-height:100vh}.card{padding:28rpx;margin-bottom:24rpx;background:#fff;border-radius:16rpx}.heading{font-size:32rpx;font-weight:700;display:block}.tip{display:block;color:#858992;font-size:25rpx;line-height:1.8;margin:20rpx 0}.record{border-bottom:1rpx solid #eee;padding:20rpx 0}textarea{width:100%;background:#f5f6f7;padding:20rpx;box-sizing:border-box;font-size:26rpx}button{margin-top:24rpx;font-size:28rpx}.danger{color:#cb4141}</style>
