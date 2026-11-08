<template>
  <view class="staff-login">
    <view class="brand-card">
      <AppImage v-if="siteStore.logo" class="logo" :src="siteStore.logo" mode="aspectFit" />
      <view v-else class="logo placeholder">工</view>
      <text class="title">{{ siteStore.siteName }}</text>
      <text class="subtitle">管理员与客服统一登录</text>
    </view>

    <view class="form-card">
      <text class="hint">登录后可进入客服工作台，并在“我的”中绑定公众号消息通知。</text>
      <view class="field">
        <text class="label">用户名</text>
        <input v-model.trim="form.username" maxlength="64" placeholder="请输入后台账号" autocomplete="username" />
      </view>
      <view class="field">
        <text class="label">密码</text>
        <input v-model="form.password" :password="true" maxlength="128" placeholder="请输入密码" autocomplete="current-password" />
      </view>
      <view class="field">
        <text class="label">图形验证码</text>
        <view class="captcha-row">
          <input v-model.trim="form.captchaCode" maxlength="6" inputmode="numeric" placeholder="6位数字" autocomplete="one-time-code" />
          <AppImage class="captcha" :src="captchaImage" mode="aspectFit" @click="refreshCaptcha" />
        </view>
      </view>
      <button class="submit" :disabled="submitting" @click="submit">{{ submitting ? '登录中…' : '登录客服工作台' }}</button>
      <text class="security">账号仅限管理员和客服使用，请勿向普通用户提供。</text>
    </view>
  </view>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AppImage from '@/components/AppImage.vue'
import { getCsLoginCaptcha, csLogin } from '@/api/auth'
import { setCsInfo, setCsToken } from '@/utils/auth'
import { useAppStore } from '@/store/app'
import { useSiteStore } from '@/store/site'

const appStore = useAppStore()
const siteStore = useSiteStore()
const form = reactive({ username: '', password: '', captchaId: '', captchaCode: '' })
const captchaImage = ref('')
const submitting = ref(false)
let redirect = '/pages-cs/dashboard/index'

onLoad(options => {
  const target = decodeURIComponent(options?.redirect || '')
  if (/^\/pages-cs\//.test(target) && !target.startsWith('/pages-cs/login/')) redirect = target
  refreshCaptcha()
})

async function refreshCaptcha() {
  try {
    const { data } = await getCsLoginCaptcha()
    form.captchaId = data?.captchaId || ''
    captchaImage.value = data?.image || ''
  } catch (_) {
    form.captchaId = ''
    captchaImage.value = ''
  } finally {
    form.captchaCode = ''
  }
}

async function submit() {
  if (submitting.value) return
  if (!form.username || !form.password) return uni.showToast({ title: '请输入用户名和密码', icon: 'none' })
  if (!form.captchaId || !/^\d{6}$/.test(form.captchaCode)) return uni.showToast({ title: '请输入6位图形验证码', icon: 'none' })
  submitting.value = true
  try {
    const { data } = await csLogin(form)
    if (!data?.token) throw new Error('登录凭据无效')
    setCsToken(data.token)
    setCsInfo(data)
    appStore.switchToCs()
    if (redirect !== '/pages-cs/dashboard/index') uni.reLaunch({ url: redirect })
  } catch (_) {
    await refreshCaptcha()
  } finally {
    submitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.staff-login { min-height: var(--app-page-height, 100vh); padding: 36rpx 28rpx calc(48rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); box-sizing: border-box; background: #f4f5f6; }
.brand-card, .form-card { width: 100%; max-width: 720rpx; margin: 0 auto; box-sizing: border-box; background: #fff; border: 1rpx solid #eceef1; border-radius: 24rpx; }
.brand-card { padding: 44rpx 28rpx; display: flex; flex-direction: column; align-items: center; }
.logo { width: 112rpx; height: 112rpx; border-radius: 24rpx; background: #17191c; }
.logo.placeholder { display: flex; align-items: center; justify-content: center; color: #fff; font-size: 44rpx; font-weight: 700; }
.title { margin-top: 20rpx; font-size: 36rpx; line-height: 1.3; font-weight: 800; color: #17191c; }
.subtitle { margin-top: 8rpx; font-size: 24rpx; color: #7b8088; }
.form-card { margin-top: 20rpx; padding: 32rpx 28rpx; }
.hint { display: block; margin-bottom: 28rpx; color: #666b73; font-size: 24rpx; line-height: 1.7; }
.field { margin-bottom: 24rpx; }
.label { display: block; margin-bottom: 10rpx; font-size: 25rpx; color: #535a63; }
.field input { width: 100%; height: 82rpx; padding: 0 22rpx; box-sizing: border-box; border: 1rpx solid #dfe3e8; border-radius: 12rpx; background: #f8f9fa; font-size: 28rpx; color: #17191c; }
.captcha-row { display: flex; align-items: center; gap: 16rpx; }
.captcha-row input { flex: 1; }
.captcha { width: 210rpx; height: 82rpx; flex: 0 0 auto; border-radius: 12rpx; background: #eef0f3; }
.submit { margin-top: 12rpx; height: 88rpx; line-height: 88rpx; border: 0; border-radius: 999rpx; background: #17191c; color: #fff; font-size: 29rpx; font-weight: 700; }
.submit[disabled] { opacity: .55; }
.security { display: block; margin-top: 22rpx; text-align: center; color: #8b9199; font-size: 22rpx; line-height: 1.6; }
</style>
