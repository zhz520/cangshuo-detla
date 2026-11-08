<template>
  <view class="refund-page">
    <view class="page-intro">
      <text class="title">退款审批</text>
      <text class="caption">{{ isAdmin ? '管理员负责终审，通过后系统才发起原路退款' : '客服负责核验与初审，最终退款由管理员审批' }}</text>
    </view>
    <scroll-view scroll-x class="status-tabs" :show-scrollbar="false">
      <text v-for="item in statuses" :key="item.value" class="status-tab" :class="{active:status===item.value}" @click="setStatus(item.value)">{{ item.label }}</text>
    </scroll-view>
    <view v-if="actionVisible" class="modal-mask" @click="actionVisible=false">
      <view class="modal-card" @click.stop>
        <text class="modal-title">{{ actionMode==='reject'?'驳回退款':'提交客服初审' }}</text>
        <view v-if="actionMode==='review'" class="recommend-row">
          <text :class="{active:recommendation==='APPROVE'}" @click="recommendation='APPROVE'">建议通过</text>
          <text :class="{active:recommendation==='REJECT'}" @click="recommendation='REJECT'">建议驳回</text>
        </view>
        <textarea v-model="actionNote" maxlength="500" :placeholder="actionMode==='reject'?'请输入驳回原因（至少2字）':'填写初审说明（可选）'" />
        <view class="modal-actions"><text @click="actionVisible=false">取消</text><text class="primary" :class="{disabled:actionBusy}" @click="submitAction">{{ actionBusy?'提交中…':'确认提交' }}</text></view>
      </view>
    </view>
    <scroll-view scroll-y class="refund-list" @scrolltolower="loadMore">
      <view v-if="error" class="state-card" @click="load(true)"><text>{{ error }}</text><text class="retry">点击重试</text></view>
      <view v-for="row in rows" :key="row.id" class="refund-card" @click="openDetail(row)">
        <view class="card-top"><text class="order-no">{{ row.orderNo || '-' }}</text><text class="status" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</text></view>
        <view class="amount-row"><text class="amount">¥{{ money(row.amount) }}</text><text class="refund-no">{{ row.refundNo }}</text></view>
        <text class="reason">{{ row.reason || '未填写退款原因' }}</text>
        <view class="card-bottom"><text>{{ formatTime(row.createdAt) }}</text><text class="detail-link">查看处理 ›</text></view>
      </view>
      <view v-if="loading" class="loading-tip">加载中…</view>
      <view v-else-if="!rows.length && !error" class="loading-tip">暂无退款申请</view>
      <view v-if="finished && rows.length" class="loading-tip">已显示全部</view>
      <view class="bottom-space" />
    </scroll-view>
  </view>
</template>

<script setup>
import {computed,ref} from 'vue'
import {onLoad,onPullDownRefresh} from '@dcloudio/uni-app'
import {getCsInfo} from '@/utils/auth'
import {getRefundList,getRefundDetail,startRefundReview,submitRefundReview,approveRefund,rejectRefund,retryRefund} from '@/pages-cs/api/cs'
const csInfo=getCsInfo()||{}
const isAdmin=computed(()=>['ADMIN','BOTH'].includes(String(csInfo.role||'').toUpperCase()))
const statuses=[{value:'',label:'全部'},{value:'REQUESTED',label:'待初审'},{value:'CS_REVIEWED',label:'待终审'},{value:'PROCESSING',label:'处理中'},{value:'REFUNDED',label:'已退款'},{value:'FAILED',label:'失败'},{value:'ADMIN_REJECTED',label:'已驳回'}]
const rows=ref([]),status=ref(''),orderNo=ref(''),page=ref(1),loading=ref(false),finished=ref(false),error=ref(''),selected=ref(null)
const actionVisible=ref(false),actionMode=ref('review'),actionNote=ref(''),activeRefund=ref(null),recommendation=ref('APPROVE'),actionBusy=ref(false)
const labels={REQUESTED:'待客服初审',CS_REVIEWING:'客服核验中',CS_REVIEWED:'待管理员终审',ADMIN_APPROVED:'管理员已批准',PROCESSING:'退款处理中',REFUNDED:'已退款',FAILED:'退款失败',ADMIN_REJECTED:'管理员已驳回',REJECTED:'已拒绝'}
const statusLabel=value=>labels[value]||value||'-'
const statusClass=value=>['REFUNDED'].includes(value)?'success':['FAILED','ADMIN_REJECTED','REJECTED'].includes(value)?'danger':['PROCESSING'].includes(value)?'warning':''
const money=value=>Number(value||0).toFixed(2)
const formatTime=value=>value?String(value).replace('T',' ').substring(0,16):'-'
function setStatus(value){status.value=value;load(true)}
async function load(reset=false){
  if(loading.value)return
  if(reset){page.value=1;rows.value=[];finished.value=false}
  loading.value=true;error.value=''
  try{
    const result=await getRefundList({pageNum:page.value,pageSize:15,status:status.value||undefined,orderNo:orderNo.value||undefined},isAdmin.value)
    const data=result.data||{},list=data.records||[]
    rows.value=page.value===1?list:[...rows.value,...list]
    finished.value=list.length<15||rows.value.length>=Number(data.total||0)
  }catch(e){error.value=e?.msg||e?.message||'退款列表加载失败'}
  finally{loading.value=false}
}
function loadMore(){if(!loading.value&&!finished.value){page.value++;load()}}
async function openDetail(row){
  selected.value=row
  try{selected.value={...row,...(await getRefundDetail(row.id,isAdmin.value)).data};showActions()}
  catch(e){uni.showToast({title:e?.msg||'详情加载失败',icon:'none'})}
}
function showActions(){
  const row=selected.value
  const items=[];const actions=[]
  if(!isAdmin.value&&row.status==='REQUESTED'){items.push('开始初审');actions.push('start')}
  if(!isAdmin.value&&['REQUESTED','CS_REVIEWING'].includes(row.status)){items.push('提交初审');actions.push('review')}
  if(isAdmin.value&&row.status==='CS_REVIEWED'){items.push('终审通过');actions.push('approve')}
  if(isAdmin.value&&['REQUESTED','CS_REVIEWING','CS_REVIEWED','FAILED'].includes(row.status)){items.push('驳回');actions.push('reject')}
  if(isAdmin.value&&row.status==='FAILED'){items.push('重试退款');actions.push('retry')}
  if(!items.length){uni.showModal({title:'退款详情',content:`订单：${row.orderNo||'-'}\n金额：¥${money(row.amount)}\n状态：${statusLabel(row.status)}\n原因：${row.reason||'-'}`,showCancel:false});return}
  uni.showActionSheet({itemList:items,success:async result=>{
    const action=actions[result.tapIndex]
    if(action==='start'){await startRefundReview(row.id);uni.showToast({title:'已开始初审',icon:'none'});await load(true)}
    if(action==='review')await reviewRefund(row)
    if(action==='approve')await confirmAction('确认终审通过并立即发起原路退款？',async()=>{await approveRefund(row.id);uni.showToast({title:'退款已提交',icon:'none'});await load(true)})
    if(action==='reject')await rejectRefundFlow(row)
    if(action==='retry')await confirmAction('确认重试渠道退款？',async()=>{await retryRefund(row.id);uni.showToast({title:'已重试',icon:'none'});await load(true)})
  }})
}
function confirmAction(content,action){return new Promise(resolve=>uni.showModal({title:'确认操作',content,success:async result=>{if(result.confirm){await action();}resolve()}}))}
function reviewRefund(row){actionMode.value='review';activeRefund.value=row;recommendation.value='APPROVE';actionNote.value='';actionVisible.value=true}
function rejectRefundFlow(row){actionMode.value='reject';activeRefund.value=row;actionNote.value='';actionVisible.value=true}
async function submitAction(){
  if(actionBusy.value)return
  const note=actionNote.value.trim()
  if(actionMode.value==='reject'&&note.length<2)return uni.showToast({title:'驳回原因至少2字',icon:'none'})
  if(actionMode.value==='review'&&recommendation.value==='REJECT'&&note.length<2)return uni.showToast({title:'建议驳回时请填写说明',icon:'none'})
  actionBusy.value=true
  try{
    if(actionMode.value==='reject')await rejectRefund(activeRefund.value.id,note)
    else await submitRefundReview(activeRefund.value.id,{recommendation:recommendation.value,note})
    actionVisible.value=false;uni.showToast({title:actionMode.value==='reject'?'已驳回':'初审已提交',icon:'none'});await load(true)
  }finally{actionBusy.value=false}
}
onLoad(options=>{
  if(options?.status)status.value=String(options.status).slice(0,32)
  if(options?.orderNo)orderNo.value=String(options.orderNo).slice(0,64)
  load(true)
})
onPullDownRefresh(async()=>{await load(true);uni.stopPullDownRefresh()})
</script>

<style lang="scss" scoped>
.refund-page{min-height:var(--app-page-height,100vh);background:#f2f3f5;overflow-x:hidden}.page-intro{padding:30rpx 26rpx 20rpx;background:#fff}.title{display:block;font-size:38rpx;font-weight:800;color:#17191c}.caption{display:block;margin-top:8rpx;font-size:23rpx;line-height:1.6;color:#7b8088}.status-tabs{white-space:nowrap;padding:16rpx 22rpx;background:#fff;border-bottom:1rpx solid #eceef1}.status-tab{display:inline-block;margin-right:12rpx;padding:11rpx 20rpx;border-radius:999rpx;background:#f2f3f5;color:#666d76;font-size:23rpx}.status-tab.active{background:#17191c;color:#fff}.refund-list{height:calc(var(--app-page-height,100vh) - 210rpx);padding:20rpx 22rpx;box-sizing:border-box}.refund-card{padding:24rpx;margin-bottom:16rpx;border-radius:20rpx;background:#fff;border:1rpx solid #eceef1;box-shadow:0 8rpx 24rpx rgba(20,24,30,.035)}.card-top,.amount-row,.card-bottom{display:flex;align-items:center;justify-content:space-between;gap:16rpx}.order-no{font-size:26rpx;font-weight:700;color:#17191c;word-break:break-all}.status{font-size:21rpx;padding:5rpx 12rpx;border-radius:999rpx;background:#eef1f5;color:#626b78}.status.success{background:#e8f2ed;color:#367258}.status.warning{background:#fff4df;color:#9b6c18}.status.danger{background:#fbecee;color:#a8444e}.amount-row{margin-top:18rpx}.amount{font-size:38rpx;font-weight:800}.refund-no{font-size:20rpx;color:#9aa0a8;word-break:break-all}.reason{display:block;margin-top:14rpx;color:#525963;font-size:24rpx;line-height:1.6}.card-bottom{margin-top:18rpx;padding-top:16rpx;border-top:1rpx solid #f1f2f4;color:#8b9199;font-size:22rpx}.detail-link{color:#17191c;font-weight:600}.loading-tip,.state-card{padding:36rpx;text-align:center;color:#8b9199;font-size:24rpx}.retry{display:block;margin-top:8rpx;color:#17191c}.bottom-space{height:40rpx}.modal-mask{position:fixed;inset:0;z-index:100;background:rgba(0,0,0,.48);display:flex;align-items:flex-end}.modal-card{width:100%;padding:30rpx 28rpx calc(28rpx + var(--app-safe-bottom,env(safe-area-inset-bottom)));border-radius:28rpx 28rpx 0 0;background:#fff;box-sizing:border-box}.modal-title{display:block;text-align:center;font-size:30rpx;font-weight:700}.recommend-row{display:flex;gap:14rpx;margin-top:22rpx}.recommend-row text{flex:1;padding:18rpx;text-align:center;border-radius:12rpx;background:#f2f3f5;color:#626b78}.recommend-row text.active{background:#17191c;color:#fff}.modal-card textarea{width:100%;height:180rpx;margin-top:20rpx;padding:20rpx;border:1rpx solid #e2e5e9;border-radius:12rpx;background:#f8f9fa;box-sizing:border-box}.modal-actions{display:flex;gap:16rpx;margin-top:22rpx}.modal-actions text{flex:1;padding:20rpx;text-align:center;border-radius:999rpx;background:#f2f3f5;color:#555c64}.modal-actions .primary{background:#17191c;color:#fff}.modal-actions .disabled{opacity:.5}
</style>
