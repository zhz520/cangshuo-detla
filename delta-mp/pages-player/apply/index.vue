<template>
  <view class="apply-page">
    <view v-if="inviteName" class="deposit-tip">邀请：{{inviteName}}</view>
    <view class="form-item"><text class="label">头像 *</text><ImageUploader v-model="avatarImages" :max="1"/><text class="upload-tip">使用 1:1 方图，建议 640×640 像素以上；头像会展示在打手列表和主页。</text></view>
    <view class="form-item"><text class="label">接单名 *</text><input v-model="form.nickname" maxlength="32" placeholder="对外展示的接单名"/></view>
    <view class="form-item"><text class="label">真实姓名</text><input v-model="form.realName" placeholder="请输入" /></view>
    <view class="form-item"><text class="label">联系电话</text><input v-model="form.phone" disabled placeholder="登录后自动使用已验证的手机号" /></view>
    <view class="form-item"><text class="label">游戏ID</text><input v-model="form.gameLevel" placeholder="请输入游戏ID或段位" /></view>
    <view class="form-item"><text class="label">擅长服务</text><input v-model="form.serviceTypes" placeholder="如：三角洲行动，无畏契约" /></view>
    <view class="form-item"><text class="label">技能标签</text><input v-model="form.skillTags" placeholder="如：突击位，信息位" /></view>
    <view class="form-item"><text class="label">证明截图</text><ImageUploader v-model="proofImages" :max="4" /></view>
    <view v-if="siteStore.depositMode === 'REQUIRED'" class="deposit-tip">申请成为打手需缴纳押金</view>
    <view v-else-if="siteStore.depositMode === 'BEFORE_WITHDRAW'" class="deposit-tip">入驻无需缴纳押金；后续提现前按平台等级要求补交押金。</view>
    <view v-else class="deposit-tip">当前平台不收取打手押金。</view>
    <view class="deposit-tip" @click="agreed=!agreed">{{agreed?'☑':'☐'}} 我已阅读并同意 <text @click.stop="goAgreement('user')">《用户协议》</text>及<text @click.stop="goAgreement('privacy')">《隐私政策》</text>。实名及手机号仅客服、管理员可见。</view>
    <view v-if="pendingDepositNo" class="deposit-tip">已返回支付页面，请确认资料后提交申请。到账状态以平台核验为准。<text @click="reopenPayment">重新打开支付</text></view>
    <view class="btn" :class="{ disabled: submitting }" @click="submit">{{ submitting ? '提交中...' : (siteStore.depositMode === 'REQUIRED' ? (pendingDepositNo ? '确认押金到账并提交' : '支付押金并提交') : '提交申请') }}</view>
    <PaymentQrDialog v-model:visible="qrVisible" :url="paymentQrUrl" title="押金支付二维码" />
  </view>
</template>
<script setup>
import { reactive, ref } from 'vue'
import {onLoad} from '@dcloudio/uni-app'
import {clientStorage} from '@/utils/client-storage'
import {get} from '@/api/request'
import {getUserProfile} from '@/api/user'
const agreed=ref(false),avatarImages=ref([]),inviteName=ref('')
let invitationToken='',deviceId=clientStorage.get('enrollment_device')
if(!deviceId){deviceId='device-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2)+'-'+Math.random().toString(36).slice(2);clientStorage.set('enrollment_device',deviceId)}
onLoad(async options => {
  await siteStore.fetchSiteConfig()
  try { const {data} = await getUserProfile(); form.phone = data.phone || '' } catch (_) {}
  invitationToken = options.invite || clientStorage.get('enrollment_invite') || ''
  if (invitationToken) {
    clientStorage.set('enrollment_invite', invitationToken)
    try { const {data} = await get('/player/invitations/' + encodeURIComponent(invitationToken), {}, {auth:false}); inviteName.value = data.name }
    catch (_) { uni.showToast({title:'邀请链接已失效',icon:'none'}) }
  }
  // #ifdef H5
  if (options.depositReturn === '1') {
    try {
      const draft = JSON.parse(sessionStorage.getItem(draftKey) || 'null')
      if (draft && draft.phone === form.phone && Date.now() - draft.savedAt < 3600000) {
        Object.assign(form, draft.form); avatarImages.value = draft.avatarImages || []; proofImages.value = draft.proofImages || []
        agreed.value = draft.agreed === true; pendingDepositNo.value = draft.paymentNo || ''
      } else sessionStorage.removeItem(draftKey)
    } catch (_) {}
  }
  // #endif
})

import ImageUploader from '@/components/ImageUploader.vue'
import { applyPlayer } from '@/api/player'
import { createPlayerDeposit, createH5PlayerDeposit, createOfficialPlayerDeposit } from '@/api/pay'
import { useSiteStore } from '@/store/site'
import { showWechatPaymentEnvironmentPrompt, wechatPayChannel } from '@/utils/platform'
import PaymentQrDialog from '@/components/PaymentQrDialog.vue'
const siteStore = useSiteStore()
const pendingDepositNo = ref('')
const qrVisible = ref(false)
const paymentQrUrl = ref('')
const draftKey = 'cangshuo-enrollment-deposit-draft'
const form = reactive({ nickname:'', realName: '', phone: '', gameLevel: '', serviceTypes: '', skillTags: '' })
const proofImages = ref([])
const submitting = ref(false)
function goAgreement(kind){uni.navigateTo({url:'/pages/agreement/'+kind})}
async function submit() {
  if (!agreed.value || !avatarImages.value.length || !form.nickname.trim() || !form.realName || !form.phone) return uni.showToast({ title: '请填写完整', icon: 'none' })
  if (submitting.value) return
  submitting.value = true
  try {
    if (siteStore.depositMode === 'REQUIRED') {
      // 需要押金：先支付再提交
      await submitWithDeposit()
    } else {
      // 无需押金：直接提交
      await applyPlayer({
        ...form,avatar:avatarImages.value[0],privacyAccepted:agreed.value,invitationToken,deviceId,
        proofImages: proofImages.value.join(',')
      })
      uni.showToast({ title: '已提交，等待审核' })
      setTimeout(() => uni.navigateBack(), 1500)
    }
  } catch (e) {
    if (!e?.errMsg?.includes('cancel')) uni.showToast({ title: e?.msg || e?.message || '操作失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

async function submitWithDeposit() {
  let paymentNo = pendingDepositNo.value
  if (!paymentNo) {
    const channel = siteStore.wxPayEnabled ? wechatPayChannel({
      miniappEnabled: siteStore.wxPayMiniappEnabled,
      h5Enabled: siteStore.wxPayH5Enabled,
      officialEnabled: siteStore.wxPayOfficialEnabled
    }) : null
    if (!channel) throw new Error('未启用可用的微信支付方式')
    if (showWechatPaymentEnvironmentPrompt(channel)) return
    if (channel === 'OFFICIAL_QR') {
      const response = await createOfficialPlayerDeposit({ scene: 'APPLY' })
      if (!response.data?.authorizeUrl || !response.data?.paymentNo) throw new Error('未获取到押金支付信息')
      pendingDepositNo.value = response.data.paymentNo
      paymentQrUrl.value = response.data.authorizeUrl
      qrVisible.value = true
      return
    }
    if (channel === 'OFFICIAL') {
      const response = await createOfficialPlayerDeposit({ scene: 'APPLY' })
      if (!response.data?.authorizeUrl || !response.data?.paymentNo) throw new Error('未获取到押金支付信息')
      // #ifdef H5
      sessionStorage.setItem(draftKey, JSON.stringify({phone:form.phone,form:{...form},avatarImages:avatarImages.value,proofImages:proofImages.value,agreed:agreed.value,paymentNo:response.data.paymentNo,savedAt:Date.now()}))
      window.location.assign(response.data.authorizeUrl)
      // #endif
      return
    }
    if (channel === 'H5') {
      const response = await createH5PlayerDeposit()
      if (!response.data?.h5Url || !response.data?.paymentNo) throw new Error('未获取到押金支付信息')
      // Only this tab retains the draft for one hour; it is removed on successful submission.
      // #ifdef H5
      sessionStorage.setItem(draftKey, JSON.stringify({phone:form.phone,form:{...form},avatarImages:avatarImages.value,proofImages:proofImages.value,agreed:agreed.value,paymentNo:response.data.paymentNo,savedAt:Date.now()}))
      const returnUrl = window.location.origin + window.location.pathname + '#/pages-player/apply/index?depositReturn=1'
      window.location.assign(response.data.h5Url + '&redirect_url=' + encodeURIComponent(returnUrl))
      // #endif
      return
    }
    if (channel !== 'MINIAPP') throw new Error('未启用可用的微信支付方式')
    const res = await createPlayerDeposit()
    const { timeStamp, nonceStr, package: pkg, signType, paySign } = res.data
    paymentNo = res.data.paymentNo
    await new Promise((resolve,reject) => uni.requestPayment({provider:'wxpay',timeStamp,nonceStr,package:pkg,signType:signType||'RSA',paySign,success:resolve,fail:reject}))
    pendingDepositNo.value = paymentNo
  }
  const doApply = () => applyPlayer({
    ...form,avatar:avatarImages.value[0],privacyAccepted:agreed.value,invitationToken,deviceId,
    proofImages: proofImages.value.join(','),
    depositPaymentNo: paymentNo
  })
  try {
    await doApply()
  } catch (e) {
    if (e?.msg?.includes('未到账')) {
      uni.showToast({ title: '支付到账中，请稍候...', icon: 'none' })
      await new Promise(r => setTimeout(r, 2500))
      await doApply()
    } else {
      throw e
    }
  }
  // #ifdef H5
  sessionStorage.removeItem(draftKey)
  // #endif
  pendingDepositNo.value = ''
  uni.showToast({ title: '已提交，等待审核' })
  setTimeout(() => uni.navigateBack(), 1500)
}
async function reopenPayment() { if (submitting.value) return; pendingDepositNo.value = ''; submitting.value = true; try { await submitWithDeposit() } catch (e) { uni.showToast({title:e?.msg||e?.message||'操作失败',icon:'none'}) } finally { submitting.value = false } }
</script>
<style lang="scss" scoped>
.apply-page { padding:24rpx; background: #ffffff; min-height: var(--app-page-height, 100vh); }
.form-item { background: #f4f5f6; padding:24rpx; border-radius:12rpx; margin-bottom:20rpx; .label { font-size:26rpx; color: #666b73; margin-bottom:12rpx; display:block; } input { height:72rpx; background: rgba(0,0,0,0.05); border-radius:8rpx; padding:0 24rpx; font-size:28rpx; } }
.deposit-tip { font-size:24rpx; color:#73777e; margin-top:16rpx; margin-bottom:8rpx; }
.upload-tip { display:block; font-size:22rpx; line-height:1.7; color:#858992; margin-top:12rpx; }
.btn { height:88rpx; line-height:88rpx; text-align:center; background: linear-gradient(135deg, #17191c, #30343a, #30343a); color:#ffffff; font-weight:bold; font-size:32rpx; border-radius:999rpx; margin-top:40rpx; &.disabled { opacity:0.7; } }
</style>
