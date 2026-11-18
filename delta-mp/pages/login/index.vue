<template>
  <view class="auth-shell" :class="{ 'login-reference': !mini }">
    <view class="auth-content" :class="{ 'login-wechat': !mini && ready && mode === 'WECHAT' }" :data-login-mode="ready ? mode.toLowerCase() : 'loading'">
      <view class="login-heading-group">
        <text class="auth-heading" role="heading" aria-level="1">{{ ready ? title : '登录 / 注册' }}</text>
        <text v-if="ready && description" class="login-description">{{ description }}</text>
      </view>
      <text v-if="loadingPolicy || exchanging" class="auth-caption" aria-live="polite">{{ exchanging ? '正在验证微信授权…' : '正在加载登录方式…' }}</text>
      <template v-else-if="policyError">
        <text class="auth-error" role="alert">登录方式加载失败</text>
        <button class="auth-primary" @click="loadPolicy">重试</button>
      </template>
      <template v-else>
        <view class="auth-form">
          <template v-if="mode === 'WECHAT'">
            <!-- #ifdef MP-WEIXIN -->
            <button v-if="needPhoneAuth && agreed" class="auth-primary" open-type="getPhoneNumber" @getphonenumber="authorizeMiniPhone">授权手机号并登录</button>
            <button v-else class="auth-primary" :disabled="busy" @click="startWechat">微信登录</button>
            <!-- #endif -->
            <!-- #ifdef H5 -->
            <button class="auth-primary login-wechat-action" :disabled="busy" @click="startWechat">
              <svg class="login-wechat-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                <path fill-rule="evenodd" d="M10 3C5.6 3 2 5.9 2 9.5c0 1.9 1 3.5 2.6 4.7l-.7 2.1 2.7-1.1c.8.3 1.6.5 2.5.6a6.6 6.6 0 0 1-.3-2c0-3.3 3.2-6 7.2-6l1.7.2C16.9 5.1 13.8 3 10 3ZM7.2 7.6a1 1 0 1 0 0 2 1 1 0 0 0 0-2Zm5.4 0a1 1 0 1 0 0 2 1 1 0 0 0 0-2Z" />
                <path fill-rule="evenodd" d="M16.1 9.1c-3.3 0-5.9 2.1-5.9 4.8s2.6 4.8 5.9 4.8c.8 0 1.6-.1 2.3-.4l2.2 1-.6-1.8c1.2-.9 2-2.2 2-3.6 0-2.7-2.6-4.8-5.9-4.8Zm-2.1 3.3a.8.8 0 1 0 0 1.6.8.8 0 0 0 0-1.6Zm4.2 0a.8.8 0 1 0 0 1.6.8.8 0 0 0 0-1.6Z" />
              </svg>
              <text>{{ busy ? '正在打开微信…' : '微信快捷登录' }}</text>
            </button>
            <text class="login-hint login-hint-center">授权后自动识别账号身份</text>
            <!-- #endif -->
          </template>
          <template v-else>
            <PhoneVerificationFields :key="mode" ref="fields" :captcha-only="mode === 'PASSWORD'"
              :send-code="mode === 'RESET' ? sendResetCode : sendLoginCode" :initial-phone="retainedPhone" :disabled="busy">
              <view v-if="mode === 'PASSWORD'" class="login-password-row">
                <input v-model="password" class="auth-input" :password="!showPassword"
                  maxlength="72" autocomplete="current-password" placeholder="登录密码" aria-label="登录密码" :disabled="busy" />
                <button class="plain login-field-action" :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                  :disabled="busy" @click="showPassword = !showPassword">{{ showPassword ? '隐藏' : '显示' }}</button>
              </view>
            </PhoneVerificationFields>
            <template v-if="mode === 'RESET'">
              <input v-model="password" class="auth-input" :password="true" maxlength="32"
                autocomplete="new-password" placeholder="新密码（8～32 位，含字母和数字）" aria-label="新密码" :disabled="busy" />
              <input v-model="confirmation" class="auth-input" :password="true" maxlength="32"
                autocomplete="new-password" placeholder="再次输入新密码" aria-label="确认密码" :disabled="busy" />
            </template>
            <view v-if="mode === 'PASSWORD'" class="login-forgot">
              <button class="plain auth-alt login-forgot-link" :disabled="busy" @click="switchMode('RESET')">忘记密码？</button>
            </view>
            <button class="auth-primary login-submit" :disabled="busy" @click="submit">{{ submitLabel }}</button>
            <text v-if="mode === 'PHONE' && !mini" class="login-hint login-hint-center">验证码验证后直接进入系统</text>
            <text v-if="mode === 'PASSWORD' && policy?.phoneEnabled && !mini" class="login-hint">尚未设置密码？请使用手机号验证码登录</text>
          </template>
        </view>

        <view class="login-footer">
          <view v-if="alternatives.length" class="login-modes">
            <text v-if="mode === 'WECHAT' && !mini" class="login-alt-caption">其他登录方式</text>
            <view class="auth-alternatives">
              <template v-for="(item, index) in alternatives" :key="item">
                <view v-if="index" class="login-alt-divider" aria-hidden="true" />
                <button class="plain auth-alt" :disabled="busy" @click="switchMode(item)">{{ mode === 'RESET' && item === 'PASSWORD' ? '返回密码登录' : labels[item] }}</button>
              </template>
            </view>
          </view>
          <view v-if="mode !== 'RESET'" class="auth-agreement">
            <checkbox-group class="auth-consent-toggle" @change="agreed = $event.detail.value.includes('accept')">
              <checkbox color="#171717" value="accept" :checked="agreed" aria-label="同意用户协议和隐私政策" />
            </checkbox-group>
            <view class="auth-agreement-copy">
              <text>我已阅读并同意</text>
              <button class="plain auth-link" @click="openTerms('user')">《用户协议》</button>
              <button class="plain auth-link" @click="openTerms('privacy')">《隐私政策》</button>
            </view>
          </view>
        </view>
      </template>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import PhoneVerificationFields from '@/components/auth/PhoneVerificationFields.vue'
import { getLoginPolicy, getWechatLoginUrl, sendLoginCode, sendResetCode, resetPassword } from '@/api/auth'
import { useUserStore } from '@/store/user'
import { clientStorage } from '@/utils/client-storage'
import { enterAfterLogin, home, removeWechatTicketFromUrl, safeLoginReturn } from '@/utils/web-login-flow'
import { isMpWeixin } from '@/utils/platform'

const users = useUserStore()
const mini = isMpWeixin()
const labels = { PHONE: '手机号登录', WECHAT: '微信登录', PASSWORD: '密码登录' }
const policy = ref(null), loadingPolicy = ref(true), policyError = ref(false), mode = ref('PHONE')
const agreed = ref(false), busy = ref(false), exchanging = ref(false), needPhoneAuth = ref(false)
const fields = ref(null), retainedPhone = ref(''), password = ref(''), confirmation = ref(''), showPassword = ref(false)
const target = ref(home)
const toast = title => uni.showToast({ title, icon: 'none' })
const ready = computed(() => !loadingPolicy.value && !exchanging.value && !policyError.value)
const title = computed(() => ({ WECHAT: mini ? '登录 / 注册' : '欢迎回来', PHONE: '手机号登录 / 注册', PASSWORD: '密码登录', RESET: '重置密码' })[mode.value])
const description = computed(() => ({
  WECHAT: mini ? '' : '登录 / 注册',
  PHONE: '未注册手机号验证成功后自动创建账号',
  PASSWORD: '仅用于已设置密码的账号',
  RESET: '验证手机号后即可重置密码'
})[mode.value])
const submitLabel = computed(() => busy.value ? '提交中…' : ({ RESET: '重置密码', PASSWORD: '登录', PHONE: mini ? '登录' : '继续' })[mode.value])
const alternatives = computed(() => {
  if (mode.value === 'RESET') return policy.value?.passwordEnabled ? ['PASSWORD'] : ['PHONE']
  const enabled = { PHONE: policy.value?.phoneEnabled, WECHAT: mini || policy.value?.wechatEnabled, PASSWORD: policy.value?.passwordEnabled }
  return [mini ? 'WECHAT' : policy.value?.primaryMethod, 'PHONE', 'WECHAT', 'PASSWORD']
    .filter((item, index, all) => enabled[item] && item !== mode.value && all.indexOf(item) === index)
})

async function loadPolicy() {
  policyError.value = false
  loadingPolicy.value = true
  try {
    const { data } = await getLoginPolicy()
    policy.value = data
    mode.value = mini ? 'WECHAT' : data.primaryMethod === 'WECHAT' ? 'WECHAT' : 'PHONE'
    if (!mini && data.reason) toast(data.reason)
  } catch (_) { policyError.value = true } finally { loadingPolicy.value = false }
}
function switchMode(next) {
  retainedPhone.value = fields.value?.phone() || retainedPhone.value
  password.value = ''
  confirmation.value = ''
  showPassword.value = false
  mode.value = next
}
function openTerms(type) { uni.navigateTo({ url: '/pages/agreement/' + type }) }
function consent() {
  if (agreed.value) return true
  toast('请先同意用户协议和隐私政策')
  return false
}

async function startWechat() {
  if (busy.value || !consent()) return
  busy.value = true
  try {
    if (mini) {
      const result = await users.login()
      if (result.success) enterAfterLogin(target.value)
      else if (result.code === 1006) needPhoneAuth.value = true
      return
    }
    // #ifdef H5
    if (!/MicroMessenger/i.test(navigator.userAgent)) {
      toast('请在微信内打开，或使用手机号登录')
      return
    }
    const back = '/pages/login/index?redirect=' + encodeURIComponent(target.value)
    const { data } = await getWechatLoginUrl(back, clientStorage.get('invite_code') || '')
    window.location.assign(data.authorizeUrl)
    // #endif
  } catch (_) {
    await loadPolicy()
  } finally { busy.value = false }
}
async function authorizeMiniPhone(event) {
  if (busy.value || !consent()) return
  if (!event.detail?.code) return toast('请授权手机号')
  busy.value = true
  try {
    const result = await users.login(event.detail.code)
    if (result.success) enterAfterLogin(target.value)
  } finally { busy.value = false }
}
async function finishWechat(ticket) {
  exchanging.value = true
  removeWechatTicketFromUrl()
  try {
    const result = await users.exchangeWechatLogin(ticket)
    if (result.bindTicket) {
      users.beginWechatPhoneBinding(result.bindTicket, target.value)
      uni.reLaunch({ url: '/pages/login/bind-phone' })
      return true
    }
    if (result.success) {
      enterAfterLogin(target.value)
      return true
    }
    toast(result.msg || '微信登录失败，请重新授权')
    return false
  } finally { exchanging.value = false }
}
async function submit() {
  if (busy.value || (mode.value !== 'RESET' && !consent())) return
  let data
  try { data = fields.value?.answer(); if (!data) return } catch (error) { return toast(error.message) }
  if (mode.value === 'PASSWORD' && !password.value) return toast('请输入登录密码')
  if (mode.value === 'RESET' && (!password.value || password.value !== confirmation.value))
    return toast('请填写新密码并确认两次输入一致')
  busy.value = true
  try {
    if (mode.value === 'RESET') {
      await resetPassword({ ...data, password: password.value, confirmation: confirmation.value })
      switchMode('PASSWORD')
      toast('密码已重置，请登录')
      return
    }
    if (mode.value === 'PASSWORD') await users.loginWithPassword({ ...data, password: password.value })
    else {
      const result = await users.loginByPhone(data.phone, data.code, data.requestId)
      if (!result.success) return
    }
    enterAfterLogin(target.value)
  } catch (_) {
    if (mode.value === 'PASSWORD') await fields.value?.refresh()
  } finally { busy.value = false }
}
onLoad(async options => {
  target.value = safeLoginReturn(options?.redirect)
  const invite = String(options?.invite || options?.inviteCode || '').trim().toUpperCase()
  if (/^[A-Z2-9]{8}$/.test(invite)) clientStorage.set('invite_code', invite)
  const ticket = String(options?.wechat_ticket || '')
  if (/^[a-f0-9]{32}$/.test(ticket) && await finishWechat(ticket)) return
  if (users.wechatPhoneBinding?.until <= Date.now()) users.clearWechatPhoneBinding()
  if (users.wechatPhoneBinding && !users.wechatPhoneBinding.accountBinding) {
    uni.reLaunch({ url: '/pages/login/bind-phone' })
    return
  }
  await loadPolicy()
  if (options?.panel === 'reset' && policy.value?.passwordEnabled) switchMode('RESET')
  if (options?.panel === 'wechat' && policy.value?.wechatEnabled) switchMode('WECHAT')
  if (options?.wechat_error === '1') toast('微信授权未完成，请重新登录')
  if (options?.expired === '1') toast('登录已过期，请重新登录')
})
</script>

<style lang="scss">
@import '@/styles/auth-flow.scss';
</style>
