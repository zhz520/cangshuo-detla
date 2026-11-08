<template>
  <view class="fulfillment-page">
    <view class="intro"><text class="title">履约审核</text><text class="caption">接力与换人合并展示，处理时仍按各自业务规则审核。</text></view>
    <view class="tabs"><text v-for="item in tabs" :key="item.value" :class="{active:type===item.value}" @click="switchType(item.value)">{{ item.label }}</text></view>
    <scroll-view scroll-y class="list" @scrolltolower="loadMore">
      <view v-if="error" class="state" @click="load(true)">{{ error }} · 点击重试</view>
      <view v-for="row in rows" :key="row.type + ':' + row.id" class="review-card" @click="openTask(row)">
        <view class="top"><text class="type-tag" :class="row.type">{{ row.type==='RELAY'?'接力':'换人' }}</text><text class="order-no">{{ row.orderNo || '-' }}</text><text class="status">{{ statusText(row.status) }}</text></view>
        <text class="product">{{ row.productName || '订单服务' }}</text>
        <text class="reason">{{ row.reason || '未填写申请原因' }}</text>
        <view class="bottom"><text>{{ row.originalPlayerName || row.playerNickname || '-' }} → {{ row.newPlayerName || '待指派' }}</text><text class="action">{{ row.status==='PENDING'?'去处理 ›':'查看 ›' }}</text></view>
      </view>
      <view v-if="loading" class="state">加载中…</view>
      <view v-else-if="!rows.length" class="state">暂无审核记录</view>
      <view class="bottom-space" />
    </scroll-view>
  </view>
</template>
<script setup>
import {ref} from 'vue'
import {onLoad,onPullDownRefresh} from '@dcloudio/uni-app'
import {getCsRelayList,getCsReplaceList} from '@/pages-cs/api/cs'
const tabs=[{value:'ALL',label:'全部'},{value:'RELAY',label:'接力'},{value:'REPLACE',label:'换人'}]
const type=ref('ALL'),rows=ref([]),loading=ref(false),error=ref('')
const pageSize=30,page=ref(1),focusTaskId=ref(null)
const finished=ref({RELAY:false,REPLACE:false})
let requestVersion=0
let version=0
const statusText=value=>({PENDING:'待处理',APPROVED:'已通过',REJECTED:'已拒绝'}[value]||value)
function switchType(value){type.value=value;load(true)}
async function load(reset=false){
  if(loading.value)return
  if(reset){
    version=++requestVersion
    page.value=1;rows.value=[];finished.value={RELAY:false,REPLACE:false}
  }else{
    version=requestVersion
  }
  const selected=type.value==='ALL'?['RELAY','REPLACE']:[type.value]
  const pending=selected.filter(key=>!finished.value[key])
  if(!pending.length)return
  loading.value=true;error.value=''
  try{
    const fetched=await Promise.all(pending.map(async key=>{
      const params={pageNum:page.value,pageSize,status:'PENDING',taskId:focusTaskId.value||undefined}
      const response=await (key==='RELAY'?getCsRelayList(params):getCsReplaceList(params))
      return [key,response.data||{}]
    }))
    if(version!==requestVersion)return
    const additions=fetched.flatMap(([key,data])=>(data.records||[]).map(row=>({...row,type:key})))
    const unique=new Map(rows.value.map(row=>[row.type+':'+row.id,row]))
    additions.forEach(row=>unique.set(row.type+':'+row.id,row))
    rows.value=[...unique.values()].sort((a,b)=>String(b.createdAt||'').localeCompare(String(a.createdAt||'')))
    fetched.forEach(([key,data])=>{
      const records=data.records||[],total=Number(data.total||0)
      const last=records.length<pageSize||(total>0&&page.value*pageSize>=total)
      finished.value={...finished.value,[key]:last||Boolean(focusTaskId.value)}
    })
    page.value+=1
  }catch(e){
    if(version===requestVersion)error.value=e?.msg||e?.message||'审核记录加载失败'
  }finally{
    if(version===requestVersion)loading.value=false
  }
}
function openTask(row){uni.navigateTo({url:row.type==='RELAY'?`/pages-cs/relay/list?taskId=${row.id}`:`/pages-cs/replace/list?taskId=${row.id}`})}
function loadMore(){
  const keys=type.value==='ALL'?['RELAY','REPLACE']:[type.value]
  if(!loading.value&&keys.some(key=>!finished.value[key]))load(false)
}
onLoad(options=>{
  const requested=String(options?.type||'').toUpperCase()
  if(requested==='RELAY'||requested==='REPLACE')type.value=requested
  focusTaskId.value=options?.taskId?String(options.taskId):null
  load(true)
})
onPullDownRefresh(async()=>{await load(true);uni.stopPullDownRefresh()})
</script>
<style lang="scss" scoped>
.fulfillment-page{min-height:var(--app-page-height,100vh);background:#f2f3f5;overflow-x:hidden}.intro{padding:28rpx 26rpx 20rpx;background:#fff}.title{display:block;font-size:38rpx;font-weight:800}.caption{display:block;margin-top:8rpx;font-size:23rpx;color:#7b8088}.tabs{display:flex;gap:14rpx;padding:16rpx 24rpx;background:#fff}.tabs text{padding:12rpx 24rpx;border-radius:999rpx;background:#f2f3f5;color:#606771;font-size:24rpx}.tabs text.active{background:#17191c;color:#fff}.list{height:calc(var(--app-page-height,100vh) - 190rpx);padding:20rpx 22rpx;box-sizing:border-box}.review-card{margin-bottom:16rpx;padding:24rpx;border-radius:20rpx;background:#fff;border:1rpx solid #eceef1}.top,.bottom{display:flex;align-items:center;justify-content:space-between;gap:12rpx}.type-tag{padding:5rpx 12rpx;border-radius:999rpx;font-size:21rpx;background:#eef1f5}.type-tag.RELAY{background:#e9f2ee;color:#39745b}.type-tag.REPLACE{background:#fff0e8;color:#a45c34}.order-no{flex:1;font-size:23rpx;color:#606771;word-break:break-all}.status{font-size:21rpx;color:#8b9199}.product{display:block;margin-top:18rpx;font-size:28rpx;font-weight:700}.reason{display:block;margin-top:10rpx;font-size:24rpx;color:#626b75;line-height:1.6}.bottom{margin-top:18rpx;padding-top:16rpx;border-top:1rpx solid #f1f2f4;font-size:22rpx;color:#8b9199}.action{color:#17191c;font-weight:700}.state{padding:40rpx;text-align:center;color:#8b9199;font-size:24rpx}.bottom-space{height:30rpx}
</style>
