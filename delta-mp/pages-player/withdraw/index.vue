<template>
  <view class="withdraw-page">
    <PageState :loading="loading && !ready" :error="loadError" @retry="load" />
    <template v-if="ready">
    <view class="balance-card">
      <text class="label">可提现金额（元）</text>
      <text class="amount">¥{{ balance }}</text>
    </view>
    <view v-if="depositStatus?.needsPayment" class="deposit-card">
      <view><text class="deposit-title">提现前需补交押金</text><text class="deposit-tip">当前等级应缴 ¥{{ Number(depositStatus.requiredAmount||0).toFixed(2) }}，已缴 ¥{{ Number(depositStatus.paidAmount||0).toFixed(2) }}，还需补交 ¥{{ Number(depositStatus.outstandingAmount||0).toFixed(2) }}</text></view>
      <button class="deposit-button" :disabled="payingDeposit" @click="payDeposit">{{ payingDeposit ? '支付中…' : '补交押金' }}</button>
    </view>
    <!-- 提现金额 -->
    <view class="form-card">
      <view class="form-item">
        <text class="item-label">提现金额</text>
        <input type="digit" v-model="form.amount" placeholder="请输入提现金额" class="input" />
        <text class="all-btn" @click="form.amount = balance">全部提现</text>
      </view>
      <!-- 选择提现账户 -->
      <view class="form-item" @click="showAccountPicker = true">
        <text class="item-label">提现账户</text>
        <text :class="['pick-text', selectedAccount ? '' : 'placeholder']">{{ selectedAccount ? accountDisplay(selectedAccount) : '请选择提现账户' }}</text>
        <text class="arrow">></text>
      </view>
      <view class="form-item">
        <text class="item-label">备注</text>
        <input v-model="form.remark" placeholder="选填" class="input" />
      </view>
    </view>
    <view class="btn-area">
      <button class="submit-btn" role="button" :disabled="submitting || loading || depositStatus?.needsPayment" @click="doSubmit">{{ submitting ? '提交中…' : depositStatus?.needsPayment ? '请先补交押金' : '确认提现' }}</button>
    </view>
    </template>
    <view class="links">
      <text class="link" @click="goList">提现记录</text>
      <text class="link" @click="goAccounts">管理账户</text>
    </view>
    <!-- 账户选择弹窗 -->
    <view v-if="showAccountPicker" class="mask" @click="showAccountPicker=false">
      <view class="picker-popup" @click.stop>
        <text class="picker-title">选择提现账户</text>
        <view v-for="a in accounts" :key="a.id" class="account-option" :class="{active: selectedAccount?.id===a.id}" @click="selectAccount(a)">
          <text>{{ accountDisplay(a) }}</text>
        </view>
<EmptyState v-if="accounts.length===0" text="暂无账户，请先添加" image="/pages-player/static/icons/暂无地址.svg" button-text="添加账户" @action="goAccounts" />
      </view>
    </view>
  </view>
</template>
<script setup>
import { ref, reactive } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import EmptyState from '@/components/EmptyState.vue'
import PageState from '@/components/PageState.vue'
import { applyWithdraw, getAccountList, getEarningsSummary, getWithdrawDepositStatus } from '@/api/player'
import { createPlayerDeposit, createH5PlayerDeposit, createOfficialPlayerDeposit } from '@/api/pay'
import { showWechatPaymentEnvironmentPrompt, wechatPayChannel } from '@/utils/platform'
import { useSiteStore } from '@/store/site'

const balance = ref('0.00')
const form = reactive({ amount: '', accountId: '', remark: '' })
const accounts = ref([])
const selectedAccount = ref(null)
const showAccountPicker = ref(false)
const depositStatus = ref(null),payingDeposit=ref(false)
const loading = ref(false), ready = ref(false), loadError = ref(''), submitting = ref(false)
const siteStore = useSiteStore()

onShow(load)
async function load() {
  if (loading.value) return
  loading.value = true; loadError.value = ''
  try {
    const [summary, accountResult, deposit] = await Promise.all([getEarningsSummary({ loading: false }), getAccountList({ loading: false }), getWithdrawDepositStatus()])
    balance.value = Number(summary.data?.balance || 0).toFixed(2)
    accounts.value = accountResult.data || []
    depositStatus.value = deposit.data
    // An account deleted in the account manager must not remain selectable here.
    const selected = accounts.value.find(a => a.id === form.accountId)
    selectedAccount.value = selected || null; form.accountId = selected?.id || ''
    ready.value = true
  } catch (_) { loadError.value = '提现信息未获取完整，请重新加载后操作。'; ready.value = false }
  finally { loading.value = false }
}
async function payDeposit(){
  if(payingDeposit.value)return
  payingDeposit.value=true
  try{
    await siteStore.fetchSiteConfig()
    const channel = siteStore.wxPayEnabled ? wechatPayChannel({
      miniappEnabled: siteStore.wxPayMiniappEnabled,
      h5Enabled: siteStore.wxPayH5Enabled,
      officialEnabled: siteStore.wxPayOfficialEnabled
    }) : null
    if (!channel) throw new Error('未启用可用的微信支付方式')
    if (showWechatPaymentEnvironmentPrompt(channel)) return
    if (channel === 'OFFICIAL') {
      const result = await createOfficialPlayerDeposit({ scene: 'WITHDRAW', role: 'player' })
      if (!result.data?.authorizeUrl) throw new Error('未获取到微信支付授权地址')
      window.location.assign(result.data.authorizeUrl)
      return
    }
    if (channel === 'H5') {
      const result = await createH5PlayerDeposit({role:'player'})
      if (!result.data?.h5Url) throw new Error('未获取到支付地址')
      // #ifdef H5
      const returnUrl = window.location.origin + window.location.pathname + '#/pages-player/withdraw/index'
      window.location.assign(result.data.h5Url + '&redirect_url=' + encodeURIComponent(returnUrl))
      // #endif
      return
    }
    if (channel !== 'MINIAPP') throw new Error('未启用可用的微信支付方式')
    const res=await createPlayerDeposit({role:'player'})
    const {paymentNo,timeStamp,nonceStr,package:pkg,signType,paySign}=res.data
    await new Promise((resolve,reject)=>uni.requestPayment({provider:'wxpay',timeStamp,nonceStr,package:pkg,signType:signType||'RSA',paySign,success:resolve,fail:reject}))
    uni.showToast({title:'支付已提交，正在确认',icon:'none'})
    await new Promise(resolve=>setTimeout(resolve,2000))
    depositStatus.value=(await getWithdrawDepositStatus()).data
  }catch(e){
    if(!e?.errMsg?.includes('cancel'))uni.showToast({title:e?.msg||e?.message||'补交押金失败',icon:'none'})
  }finally{payingDeposit.value=false}
}

function accountDisplay(a) { return `${a.type === 'ALIPAY' ? '支付宝' : a.type === 'WECHAT' ? '微信' : a.type === 'BANK' ? '银行卡' : a.type} ${(a.accountNo || a.account || '').replace(/(.{3}).*(.{4})/, '$1****$2')}` }
function selectAccount(a) { selectedAccount.value = a; form.accountId = a.id; showAccountPicker.value = false }
async function doSubmit() {
  if (submitting.value || loading.value || !ready.value) return
  if (depositStatus.value?.needsPayment) return uni.showToast({ title: '请先补交押金', icon: 'none' })
  if (!/^\d+(\.\d{1,2})?$/.test(String(form.amount)) || !Number.isFinite(Number(form.amount)) || Number(form.amount) <= 0) return uni.showToast({ title: '请输入正确金额，最多两位小数', icon: 'none' })
  if (Number(form.amount) > Number(balance.value)) return uni.showToast({ title: '超过可提现金额', icon: 'none' })
  if (!form.accountId) return uni.showToast({ title: '请选择提现账户', icon: 'none' })
  submitting.value = true
  try {
    await applyWithdraw({ amount: Number(form.amount), accountId: form.accountId, remark: form.remark })
    uni.showToast({ title: '提现申请已提交' })
    uni.redirectTo({ url: '/pages-player/withdraw/list' })
  } catch (e) { submitting.value = false }
}
function goList() { uni.navigateTo({ url: '/pages-player/withdraw/list' }) }
function goAccounts() { uni.navigateTo({ url: '/pages-player/account/list' }) }
</script>
<style lang="scss" scoped>
.withdraw-page { background: #ffffff; min-height: var(--app-page-height, 100vh); }
.balance-card { background: linear-gradient(135deg, #17191c, #30343a, #30343a); color: #ffffff; font-weight:bold; margin: 24rpx; border-radius: 16rpx; padding: 40rpx 32rpx;
  .label { font-size: 26rpx; opacity: 0.85; display: block; } .amount { font-size: 56rpx; font-weight: bold; display: block; margin-top: 12rpx; }
}
.deposit-card{margin:24rpx;padding:24rpx;border:1rpx solid #ead8bd;border-radius:12rpx;background:#fff8ed;display:flex;flex-direction:column;gap:20rpx}.deposit-title{display:block;font-size:28rpx;font-weight:700;color:#17191c}.deposit-tip{display:block;margin-top:8rpx;color:#73777e;font-size:24rpx;line-height:1.7}.deposit-button{align-self:flex-start;margin:0;padding:14rpx 28rpx;border-radius:999rpx;background:#17191c;color:#fff;font-size:26rpx}
.form-card { margin: 24rpx; background: #f4f5f6; border-radius: 12rpx; padding: 0 24rpx; }
.form-item { display: flex; align-items: center; padding: 28rpx 0; border-bottom: 1rpx solid #f4f5f6;
  .item-label { font-size: 28rpx; color: #17191c; width: 160rpx; } .input { flex: 1; font-size: 28rpx; }
  .pick-text { flex: 1; font-size: 28rpx; color: #17191c; &.placeholder { color: #b9bdc4; } } .arrow { color: #b9bdc4; } .all-btn { font-size: 26rpx; color: #17191c; }
}
.btn-area { padding: 40rpx 24rpx 16rpx; }
.submit-btn { background: linear-gradient(135deg, #17191c, #30343a, #30343a); color: #ffffff; font-weight:bold; text-align: center; padding: 24rpx; border-radius: 999rpx; font-size: 30rpx; }
.links { display: flex; justify-content: center; gap: 48rpx; padding: 24rpx; .link { font-size: 26rpx; color: #17191c; } }
.mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); display: flex; align-items: flex-end; z-index: 999; }
.picker-popup { width: 100%; background: #ffffff; border-radius: 24rpx 24rpx 0 0; border-top: 1rpx solid #e6e7e9; padding: 32rpx 24rpx; max-height: 60vh; overflow-y: auto;
  .picker-title { font-size: 30rpx; font-weight: bold; display: block; margin-bottom: 24rpx; text-align: center; }
}
.account-option { padding: 24rpx; border-bottom: 1rpx solid #f4f5f6; font-size: 28rpx; &.active { color: #17191c; background: #f7f7f8; border-radius: 8rpx; } }
</style>
