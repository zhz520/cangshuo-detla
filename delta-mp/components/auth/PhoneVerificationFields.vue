<template>
  <view class="verify-fields">
    <input :value="phone" class="auth-input" type="number" maxlength="11" placeholder="请输入手机号"
      aria-label="手机号" autocomplete="tel" :disabled="disabled" @input="input('phone', $event)" />
    <slot />
    <view class="verify-row">
      <input :value="captcha" class="auth-input" type="number" maxlength="6" placeholder="请输入图形验证码"
        aria-label="图形验证码" :disabled="disabled" @input="input('captcha', $event)" />
      <button class="secondary verify-side captcha-button" type="default" :disabled="disabled || loadingCaptcha"
        aria-label="刷新图形验证码" @click="refresh">
        <image v-if="image" class="captcha-image" :src="image" mode="aspectFit" />
        <text v-else>{{ loadingCaptcha ? '加载中…' : '获取图片' }}</text>
      </button>
    </view>
    <view v-if="!captchaOnly" class="verify-row">
      <input :value="code" class="auth-input" type="number" maxlength="6" placeholder="请输入短信验证码"
        aria-label="短信验证码" autocomplete="one-time-code" :disabled="disabled" @input="input('code', $event)" />
      <button class="secondary verify-side" type="default" :disabled="disabled || sending || countdown > 0 || !captchaId" @click="send">
        {{ sending ? '发送中…' : countdown ? countdown + '秒后重试' : '获取验证码' }}
      </button>
    </view>
  </view>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { getLoginCaptcha } from '@/api/auth'

const props = defineProps({
  initialPhone: { type: String, default: '' },
  captchaOnly: Boolean,
  disabled: Boolean,
  sendCode: Function,
})
const phone = ref(props.initialPhone), captcha = ref(''), captchaId = ref(''), image = ref('')
const code = ref(''), requestId = ref(''), sentTo = ref(''), countdown = ref(0)
const loadingCaptcha = ref(false), sending = ref(false)
let timer, disposed = false

function input(field, event) {
  const value = String(event?.detail?.value ?? '')
  if (field === 'phone') {
    phone.value = value
    if (value !== sentTo.value) {
      requestId.value = ''
      code.value = ''
    }
  } else if (field === 'captcha') captcha.value = value
  else code.value = value
}
async function refresh() {
  if (loadingCaptcha.value || disposed) return
  loadingCaptcha.value = true
  captchaId.value = ''
  image.value = ''
  captcha.value = ''
  try {
    const { data } = await getLoginCaptcha()
    if (!disposed) {
      captchaId.value = data.captchaId
      image.value = data.image
    }
  } catch (_) {} finally { loadingCaptcha.value = false }
}
async function send() {
  if (disposed || props.disabled || sending.value || countdown.value || !captchaId.value) return
  if (!/^1\d{10}$/.test(phone.value) || !/^\d{6}$/.test(captcha.value))
    return uni.showToast({ title: '请填写手机号和图形验证码', icon: 'none' })
  const destination = phone.value
  sending.value = true
  try {
    const { data } = await props.sendCode({ phone: destination, captchaId: captchaId.value, captchaCode: captcha.value })
    if (disposed) return
    if (phone.value === destination) {
      requestId.value = data.requestId
      sentTo.value = destination
    }
    const until = Date.now() + 1000 * (Number(data.retryAfter) || 60)
    const tick = () => {
      countdown.value = Math.max(0, Math.ceil((until - Date.now()) / 1000))
      if (!countdown.value) clearInterval(timer)
    }
    timer = setInterval(tick, 1000)
    tick()
    uni.showToast({ title: '验证码已发送', icon: 'none' })
  } catch (_) {
    if (!disposed) await refresh()
  } finally { sending.value = false }
}
function answer() {
  if (!/^1\d{10}$/.test(phone.value))
    throw new Error('请输入正确的手机号')
  if (props.captchaOnly) {
    if (!captchaId.value || !/^\d{6}$/.test(captcha.value)) throw new Error('请输入图形验证码')
    return { phone: phone.value, captchaId: captchaId.value, captchaCode: captcha.value }
  }
  if (!requestId.value || sentTo.value !== phone.value) throw new Error('请先获取短信验证码')
  if (!/^\d{6}$/.test(code.value)) throw new Error('请输入短信验证码')
  return { phone: phone.value, requestId: requestId.value, code: code.value }
}
defineExpose({ answer, refresh, phone: () => phone.value })
onMounted(refresh)
onUnmounted(() => { disposed = true; clearInterval(timer) })
</script>

<style scoped>
.verify-fields { display:grid; gap:12px; min-width:0 }
.verify-row { display:grid; grid-template-columns:minmax(0,1fr) minmax(98px, 34%); gap:8px; align-items:stretch }
.verify-side { display:flex; align-items:center; justify-content:center; margin:0; padding:0 6px; min-height:46px; line-height:1.2; font-size:12px; white-space:nowrap; color:var(--auth-text,var(--store-text)); background:var(--auth-field,var(--store-surface)); border:1px solid var(--auth-border,#e5e5e5); border-radius:12px }
.verify-side::after { border:0 }
.verify-side[disabled] { opacity:.65 }
.captcha-button { overflow:hidden; padding:0 }
.captcha-image { display:block; height:40px; width:100%; max-width:124px }
</style>
