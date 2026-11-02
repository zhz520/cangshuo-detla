<template><view class="binding-page"><text class="title">公众号消息通知</text><text class="tip">绑定微信后，可接收订单、素材审核、提现审核等服务通知。需关注平台已配置的公众号，并具备对应模板消息权限。</text><text>{{status.enabled?(status.bound?'已绑定':'尚未绑定'):'平台尚未启用公众号通知'}}</text><button v-if="status.enabled&&!status.bound" :disabled="busy" @click="start">在微信内绑定</button><button v-if="status.bound" :disabled="busy" @click="remove">解除绑定</button><text v-if="message" class="tip">{{message}}</text></view></template>
<script setup>
import {reactive,ref} from 'vue'
import {onLoad} from '@dcloudio/uni-app'
import {get,post,del} from '@/api/request'
import {clientStorage} from '@/utils/client-storage'
const status=reactive({enabled:false,bound:false}),busy=ref(false),message=ref('')
let role='user'
async function load(){Object.assign(status,(await get('/common/wechat/binding',{}, {role})).data)}
onLoad(async options=>{role=options.role||clientStorage.get('wechat_binding_role')||'user';if(!['user','player','cs'].includes(role))role='user';busy.value=true;try{if(options.code&&options.state){await post('/common/wechat/binding/complete',{code:options.code,state:options.state},{role});message.value='绑定成功'}await load()}catch(_){message.value='请确认已登录对应身份后重试绑定'}finally{busy.value=false}})
async function start(){
 // #ifdef H5
 if(!/MicroMessenger/i.test(navigator.userAgent))return uni.showToast({title:'请在微信内打开此页面进行绑定',icon:'none'})
 // #endif
 busy.value=true;try{const {data}=await post('/common/wechat/binding/start',{}, {role});clientStorage.set('wechat_binding_role',role);
 // #ifdef H5
 window.location.assign(data)
 // #endif
 // #ifndef H5
 uni.showToast({title:'请在平台H5网页内完成绑定',icon:'none'})
 // #endif
 }catch(_){}finally{busy.value=false}
}
async function remove(){const result=await uni.showModal({title:'解除通知绑定',content:'解除后将不再接收此身份的公众号服务通知。'});if(!result.confirm)return;busy.value=true;try{await del('/common/wechat/binding',{}, {role});await load()}catch(_){}finally{busy.value=false}}
</script>
<style scoped>.binding-page{padding:40rpx;background:#fff;min-height:100vh}.title{font-size:36rpx;font-weight:700;display:block}.tip{display:block;color:#858992;font-size:26rpx;line-height:1.8;margin:32rpx 0}button{margin-top:36rpx;font-size:28rpx}</style>
