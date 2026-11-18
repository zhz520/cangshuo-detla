<template>
  <view class="auth-shell">
    <view class="auth-content">
      <text class="auth-heading">{{ flow?.takeover ? '验证手机号' : '绑定手机号' }}</text>
      <text v-if="expired && !error" class="auth-caption">微信绑定凭据已过期，请重新授权。</text>
      <button v-if="expired" class="auth-primary" @click="restart">{{ flow?.accountBinding ? '返回账号安全' : '返回登录' }}</button>
      <template v-else-if="flow?.confirmationTicket">
        <text class="auth-caption">{{ flow.takeover ? '手机号已验证。确认后，本次授权的微信将绑定到当前账号；不转移订单或资产。' : '该手机号已有账号。确认后将使用原手机号账号，保留该账号的订单和资产，并用本次授权的微信替换原微信绑定（如有）。' }}</text>
        <button class="auth-primary" :disabled="busy" @click="finishMerge">{{ busy ? '处理中…' : flow.takeover ? '确认转移微信绑定' : '确认合并' }}</button>
      </template>
      <template v-else>
        <text class="auth-caption">{{ flow?.takeover ? `请验证当前账号已绑定的手机号 ${flow.phone}，验证成功后才能确认转移微信绑定。` : '验证手机号后即可完成绑定。' }}</text>
        <PhoneVerificationFields ref="fields" :send-code="sendCode" :disabled="busy" />
        <button class="auth-primary" :disabled="busy" @click="bind">{{ busy ? '验证中…' : '确认绑定' }}</button>
      </template>
      <button v-if="!expired" class="auth-alt" :disabled="busy" @click="restart">{{ flow?.accountBinding ? '取消并返回账号安全' : '取消，选择其他登录方式' }}</button>
      <text v-if="error" class="auth-error" role="alert">{{ error }}</text>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onBackPress, onShow } from '@dcloudio/uni-app'
import PhoneVerificationFields from '@/components/auth/PhoneVerificationFields.vue'
import { sendWechatPhoneCode, verifyWechatPhone, confirmWechatPhone } from '@/api/auth'
import { useUserStore } from '@/store/user'
import { enterAfterLogin, safeLoginReturn } from '@/utils/web-login-flow'

const users = useUserStore()
const flow = computed(() => users.wechatPhoneBinding)
const fields = ref(null), busy = ref(false), error = ref(''), expired = ref(false)
const sessionLost = failure => Number(failure?.code) === 401
const loginPath = '/pages/login/index'

function restart() {
  if (busy.value) return
  const target = safeLoginReturn(flow.value?.target)
  const accountBinding = flow.value?.accountBinding
  users.clearWechatPhoneBinding()
  uni.reLaunch({ url: accountBinding ? target : loginPath + '?redirect=' + encodeURIComponent(target) })
}
function fail(failure) {
  error.value = Number(failure?.code) === 422 && flow.value?.takeover
    ? '原微信账号受保护，不能自动接管。请在原账号绑定未注册的手机号，或联系平台客服。'
    : failure?.msg || failure?.message || '手机号验证失败，请重试'
  if (sessionLost(failure)) expired.value = true
}
async function sendCode(data) {
  error.value = ''
  try { return await sendWechatPhoneCode({ ...data, ticket: flow.value.ticket }, flow.value.accountBinding) }
  catch (failure) { fail(failure); throw failure }
}
function finish(data) {
  const target = flow.value.target
  users.acceptWebLogin(data)
  enterAfterLogin(target)
}
async function bind() {
  if (busy.value) return
  let data
  try { data = fields.value.answer() } catch (failure) { return fail(failure) }
  busy.value = true
  error.value = ''
  try {
    const result = await verifyWechatPhone({ ...data, ticket: flow.value.ticket }, flow.value.accountBinding)
    if (result.data?.confirmationTicket) users.confirmWechatPhoneBinding(result.data.confirmationTicket)
    else finish(result.data)
  } catch (failure) {
    fail(failure)
    if (Number(failure?.code) === 409 || (Number(failure?.code) === 422 && flow.value?.takeover)) expired.value = true
  } finally { busy.value = false }
}
async function finishMerge() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const { data } = await confirmWechatPhone({ ticket: flow.value.confirmationTicket }, flow.value.accountBinding)
    finish(data)
  } catch (failure) {
    fail(failure)
    expired.value = true
  } finally { busy.value = false }
}
onShow(() => { if (!flow.value || flow.value.until <= Date.now()) expired.value = true })
onBackPress(() => { restart(); return true })
</script>

<style lang="scss">
@import '@/styles/auth-flow.scss';
</style>
