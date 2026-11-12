<template>
  <view class="bind-page">
    <text class="title">绑定手机号</text>
    <text class="hint">微信登录成功。绑定手机号后可使用短信登录和找回账号。</text>
    <input v-model="phone" type="number" maxlength="11" class="input" placeholder="请输入手机号" />
    <view class="row">
      <input v-model="captchaCode" maxlength="6" class="input" placeholder="图形验证码" />
      <AppImage :src="captchaImage" class="captcha" mode="aspectFit" @click="loadCaptcha" />
    </view>
    <view class="row">
      <input v-model="code" type="number" maxlength="6" class="input" placeholder="短信验证码" />
      <button class="send" :disabled="sending || countdown > 0" @click="sendCode">{{ countdown ? countdown + '秒后重试' : '获取验证码' }}</button>
    </view>
    <view class="primary" @click="bind">{{ binding ? '绑定中...' : '确认绑定' }}</view>
    <view class="skip" @click="skip">稍后绑定</view>
  </view>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AppImage from '@/components/AppImage.vue'
import { getLoginCaptcha } from '@/api/auth'
import { sendPhoneBindCode, updatePhone } from '@/api/user'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const phone = ref('')
const code = ref('')
const captchaCode = ref('')
const captchaId = ref('')
const captchaImage = ref('')
const requestId = ref('')
const sending = ref(false)
const binding = ref(false)
const countdown = ref(0)
const redirectUrl = ref('/pages/index/index')
let timer

async function loadCaptcha() {
  try {
    const { data } = await getLoginCaptcha()
    captchaId.value = data.captchaId
    captchaImage.value = data.image
    captchaCode.value = ''
  } catch (_) {}
}

async function sendCode() {
  if (sending.value || countdown.value) return
  if (!/^1\d{10}$/.test(phone.value) || !captchaCode.value)
    return uni.showToast({ title: '请填写手机号和图形验证码', icon: 'none' })
  sending.value = true
  try {
    const { data } = await sendPhoneBindCode({
      phone: phone.value, purpose: 'BIND_NEW', captchaId: captchaId.value, captchaCode: captchaCode.value
    })
    requestId.value = data.requestId
    countdown.value = Number(data.retryAfter) || 60
    clearInterval(timer)
    timer = setInterval(() => { if (--countdown.value <= 0) clearInterval(timer) }, 1000)
    uni.showToast({ title: '验证码已发送', icon: 'none' })
  } catch (_) {
    await loadCaptcha()
  } finally {
    sending.value = false
  }
}

async function bind() {
  if (binding.value) return
  if (!/^1\d{10}$/.test(phone.value) || !requestId.value || !code.value)
    return uni.showToast({ title: '请填写手机号和短信验证码', icon: 'none' })
  binding.value = true
  try {
    try {
      await updatePhone({ phone: phone.value, requestId: requestId.value, code: code.value })
    } catch (error) {
      if (error?.code !== 409) throw error
      await userStore.linkWechatAccount({ phone: phone.value, requestId: requestId.value, code: code.value })
    }
    await userStore.fetchProfile()
    uni.reLaunch({ url: redirectUrl.value })
  } catch (_) {
    await loadCaptcha()
  } finally {
    binding.value = false
  }
}

function skip() {
  uni.reLaunch({ url: redirectUrl.value })
}

onLoad(options => {
  const target = decodeURIComponent(options?.redirect || '')
  if (/^\/(pages|pages-player)\//.test(target)) redirectUrl.value = target
})
onMounted(loadCaptcha)
onUnmounted(() => clearInterval(timer))
</script>

<style lang="scss" scoped>
.bind-page { min-height: var(--app-page-height, 100vh); padding: 72rpx 52rpx; background: #f7f8f9; box-sizing: border-box; }
.title { display: block; font-size: 40rpx; font-weight: 700; color: #17191c; }
.hint { display: block; margin: 16rpx 0 38rpx; color: #73777e; line-height: 1.7; font-size: 25rpx; }
.input { width: 100%; height: 84rpx; margin-bottom: 22rpx; padding: 0 24rpx; border-radius: 12rpx; background: #fff; box-sizing: border-box; font-size: 27rpx; }
.row { display: flex; align-items: center; gap: 12rpx; margin-bottom: 22rpx; }
.row .input { flex: 1; min-width: 0; margin-bottom: 0; }
.captcha { width: 180rpx; height: 84rpx; border-radius: 12rpx; background: #fff; }
.send { width: 190rpx; height: 84rpx; line-height: 84rpx; padding: 0; border-radius: 12rpx; background: #eceef0; color: #17191c; font-size: 24rpx; }
.primary { margin-top: 22rpx; height: 88rpx; line-height: 88rpx; text-align: center; border-radius: 12rpx; background: #17191c; color: #fff; font-size: 28rpx; }
.skip { padding: 28rpx; text-align: center; color: #73777e; font-size: 25rpx; }
</style>
