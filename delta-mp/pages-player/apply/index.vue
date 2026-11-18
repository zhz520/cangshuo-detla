<template>
  <view class="apply-page">
    <view v-if="inviteName" class="deposit-tip">邀请：{{inviteName}}</view>
    <view v-if="inviteIssue" class="apply-error" role="alert">{{ inviteIssue }}</view>
    <view class="apply-section">
      <text class="section-heading">基本资料</text>
      <view class="form-item"><text class="label">头像 *</text><ImageUploader v-model="avatarImages" :max="1"/>
        <text class="upload-tip">1:1 方图，建议 640×640 像素以上</text>
        <text v-if="fieldError.key === 'avatar' && !avatarImages.length" class="apply-error" role="alert">{{ fieldError.message }}</text>
      </view>
      <view class="form-item"><text class="label">接单名 *</text><input v-model="form.nickname" aria-label="接单名" maxlength="32" :disabled="locked" :class="{invalid: fieldError.key === 'nickname'}" @input="clearField('nickname')"/>
        <text v-if="fieldError.key === 'nickname'" class="apply-error" role="alert">{{ fieldError.message }}</text>
      </view>
      <view class="form-item"><text class="label">真实姓名 *</text><input v-model="form.realName" aria-label="真实姓名" :disabled="locked" :class="{invalid: fieldError.key === 'realName'}" @input="clearField('realName')"/>
        <text v-if="fieldError.key === 'realName'" class="apply-error" role="alert">{{ fieldError.message }}</text>
      </view>
      <view class="form-item"><text class="label">联系电话 *</text><input v-model="form.phone" aria-label="联系电话" disabled placeholder="未绑定" />
        <text v-if="fieldError.key === 'phone'" class="apply-error" role="alert">{{ fieldError.message }}</text>
      </view>
    </view>
    <view class="apply-section">
      <text class="section-heading">服务资料</text>
      <view class="form-item"><text class="label">游戏 ID / 段位</text><input v-model="form.gameLevel" aria-label="游戏 ID 或段位" :disabled="locked" /></view>
      <view class="form-item"><text class="label">擅长服务</text><input v-model="form.serviceTypes" aria-label="擅长服务" :disabled="locked" /></view>
      <view class="form-item"><text class="label">技能标签</text><input v-model="form.skillTags" aria-label="技能标签" :disabled="locked" /></view>
      <view class="form-item"><text class="label">证明截图</text><ImageUploader v-model="proofImages" :max="4" /></view>
    </view>
    <view class="apply-footer">
    <view v-if="siteStore.depositMode === 'REQUIRED'" class="deposit-tip">申请成为打手需缴纳押金</view>
    <view v-else-if="siteStore.depositMode === 'BEFORE_WITHDRAW'" class="deposit-tip">入驻无需缴纳押金；后续提现前按平台等级要求补交押金。</view>
    <view class="agreement-row">
      <checkbox-group @change="agreed = $event.detail.value.includes('agree'); clearField('agreement')">
        <label><checkbox value="agree" :checked="agreed" :disabled="locked" color="#171717" />我已阅读并同意</label>
      </checkbox-group>
      <text @click="goAgreement('user')">《用户协议》</text>
      <text @click="goAgreement('privacy')">《隐私政策》</text>
    </view>
    <text v-if="fieldError.key === 'agreement'" class="apply-error" role="alert">{{ fieldError.message }}</text>
    <text v-if="submitError" class="apply-error" role="alert">{{ submitError }}</text>
    <view v-if="pendingDepositNo" class="deposit-tip">已返回支付页面，请确认资料后提交申请。到账状态以平台核验为准。<text @click="reopenPayment">重新打开支付</text></view>
    <button class="apply-submit" :disabled="locked" @click="submit">{{ submitted ? '已提交' : submitting ? '正在提交' : (siteStore.depositMode === 'REQUIRED' ? (pendingDepositNo ? '确认押金到账并提交' : '支付押金并提交') : '提交申请') }}</button>
    </view>
    <PaymentQrDialog v-model:visible="qrVisible" :url="paymentQrUrl" title="押金支付二维码" />
  </view>
</template>
<script setup>
import { computed, reactive, ref } from 'vue'
import {onLoad, onUnload} from '@dcloudio/uni-app'
import {clientStorage} from '@/utils/client-storage'
import {get} from '@/api/request'
import {getUserProfile} from '@/api/user'
const agreed=ref(false),avatarImages=ref([]),inviteName=ref(''),inviteIssue=ref('')
let invitationToken='',deviceId=clientStorage.get('enrollment_device')
if(!deviceId){deviceId='device-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2)+'-'+Math.random().toString(36).slice(2);clientStorage.set('enrollment_device',deviceId)}
onLoad(async options => {
  try { await siteStore.fetchSiteConfig() } catch (_) {}
  try { const {data} = await getUserProfile({ loading: false, silent: true }); form.phone = data.phone || '' } catch (_) {}
  invitationToken = options.invite || clientStorage.get('enrollment_invite') || ''
  if (invitationToken) await verifyInvitation()
  loading.value = false
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
import { applyPlayer, getPlayerApplicationStatus } from '@/api/player'
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
const submitting = ref(false), submitted = ref(false), loading = ref(true)
const locked = computed(() => submitting.value || submitted.value || loading.value)
const fieldError = ref({}), submitError = ref('')
let returnTimer
onUnload(() => clearTimeout(returnTimer))
function clearField(key) { if (fieldError.value.key === key) fieldError.value = {} }
function complete() {
  submitted.value = true
  uni.showToast({ title: '已提交，等待审核' })
  returnTimer = setTimeout(() => uni.navigateBack(), 850)
}
function valid() {
  const invalid = [
    ['avatar', avatarImages.value.length, '请上传头像'],
    ['nickname', form.nickname.trim(), '请输入接单名'],
    ['realName', form.realName.trim(), '请输入真实姓名'],
    ['phone', form.phone, '请先在账号设置绑定手机号'],
    ['agreement', agreed.value, '请同意用户协议和隐私政策']
  ].find(([, valid]) => !valid)
  fieldError.value = invalid ? { key: invalid[0], message: invalid[2] } : {}
  return !invalid
}
async function verifyInvitation() {
  inviteIssue.value = ''
  if (!invitationToken) return true
  try {
    const {data} = await get('/player/invitations/' + encodeURIComponent(invitationToken), {}, {auth:false, silent:true, loading:false})
    inviteName.value = data.name
    clientStorage.set('enrollment_invite', invitationToken)
    return true
  } catch (error) {
    inviteName.value = ''
    if ([400,404].includes(Number(error?.code))) {
      clientStorage.remove('enrollment_invite')
      inviteIssue.value = '邀请链接已停用或过期，请向邀请人获取新链接。'
    } else inviteIssue.value = '暂时无法核验邀请，请重试。'
    return false
  }
}
const applyOptions = { silent: true, loading: false }
function goAgreement(kind){uni.navigateTo({url:'/pages/agreement/'+kind})}
async function submit() {
  if (locked.value) return
  submitError.value = ''
  if (!valid()) return
  submitting.value = true
  try {
    if (!(await verifyInvitation())) return
    if (siteStore.depositMode === 'REQUIRED') {
      const { data } = await getPlayerApplicationStatus()
      if (data?.depositSatisfied) {
        // Browser may have been refreshed or the payment completed on another device.
        await submitApplication()
        return
      }
      await submitWithDeposit()
    } else {
      // 无需押金：直接提交
      await submitApplication()
    }
  } catch (e) {
    if (!e?.errMsg?.includes('cancel')) submitError.value = e?.msg || e?.message || '提交失败，请重试'
  } finally {
    submitting.value = false
  }
}

async function submitApplication() {
  await applyPlayer({
    ...form, avatar: avatarImages.value[0], privacyAccepted: agreed.value, invitationToken, deviceId,
    proofImages: proofImages.value.join(',')
  }, applyOptions)
  // #ifdef H5
  sessionStorage.removeItem(draftKey)
  // #endif
  pendingDepositNo.value = ''
  complete()
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
  }, applyOptions)
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
  complete()
}
async function reopenPayment() {
  if (locked.value || !valid()) return
  submitError.value = ''
  const previous = pendingDepositNo.value
  pendingDepositNo.value = ''
  submitting.value = true
  try { await submitWithDeposit() }
  catch (e) { submitError.value = e?.msg || e?.message || '无法打开支付，请重试' }
  finally {
    if (!submitted.value && !pendingDepositNo.value) pendingDepositNo.value = previous
    submitting.value = false
  }
}
</script>
<style lang="scss" scoped>
.apply-page { box-sizing: border-box; padding: 24rpx 24rpx calc(28rpx + env(safe-area-inset-bottom)); background: var(--store-soft); min-height: var(--app-page-height, 100vh); }
.apply-section, .apply-footer { padding: 22rpx 26rpx; border-radius: 24rpx; background: var(--store-surface); margin-bottom: 18rpx; }
.section-heading { display: block; font-size: 30rpx; font-weight: 750; margin-bottom: 8rpx; }
.form-item { padding: 16rpx 0; }
.form-item + .form-item { border-top: 1rpx solid var(--store-line); }
.label { display: block; margin-bottom: 8rpx; font-size: 27rpx; font-weight: 600; }
.form-item input { box-sizing: border-box; min-height: 44px; padding: 0 16rpx; border-radius: 12rpx; background: var(--store-soft); font-size: 28rpx; box-shadow: inset 0 0 0 1px transparent; }
.form-item input:focus-within { box-shadow: inset 0 0 0 1px #777; }
.form-item input.invalid { box-shadow: inset 0 0 0 1px #a63842; }
.form-item input[disabled] { color: #777; }
.form-item :deep(.uni-input-input) { outline: none; }
.deposit-tip { display: block; font-size: 24rpx; color: var(--store-regular); margin: 10rpx 0 16rpx; }
.deposit-tip text, .agreement-row > text { color: var(--store-text); text-decoration: underline; }
.upload-tip { display: block; font-size: 22rpx; color: var(--store-muted); margin-top: 10rpx; }
.agreement-row { display: flex; align-items: center; flex-wrap: wrap; font-size: 24rpx; line-height: 1.6; column-gap: 4rpx; }
.agreement-row label { display: flex; align-items: center; min-height: 44px; }
.apply-error { display: block; color: #a63842; font-size: 24rpx; margin-top: 8rpx; }
.apply-submit { margin-top: 18rpx; min-height: 44px; padding: 12rpx; background: var(--store-primary); color: #fff; font-weight: 700; line-height: 1.5; font-size: 28rpx; border-radius: 16rpx; }
.apply-submit::after { border: 0; }
</style>
