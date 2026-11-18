<template>
  <view class="auth-shell security-shell">
    <view class="auth-content">
      <ScreenHeader title="账号安全" back center />
      <text v-if="loading" class="auth-caption">正在加载…</text>
      <template v-else-if="failed">
        <text class="auth-error">账号状态加载失败</text>
        <button class="auth-primary" @click="refresh">重试</button>
      </template>
      <template v-else-if="status">
        <view class="security-card">
          <view class="security-row"><text>手机号</text><text class="auth-caption">{{ status.phone || '未绑定' }}</text></view>
          <view class="security-row">
            <view><text>微信账号</text><text class="security-summary">{{ status.wechatBound ? '已绑定' : '未绑定' }}</text></view>
            <button v-if="status.wechatBound && status.phoneBound" class="auth-alt" :disabled="busy" @click="askUnbind">解绑</button>
            <!-- #ifdef H5 -->
            <button v-if="!status.wechatBound && status.wechatAvailable && status.phoneBound" class="auth-alt" :disabled="busy" @click="bind">去绑定</button>
            <!-- #endif -->
          </view>
          <view class="security-row">
            <view><text>登录密码</text><text class="security-summary">{{ status.passwordSet ? '已设置' : '未设置' }}</text></view>
            <button v-if="status.phoneBound" class="auth-alt" :disabled="busy" @click="showForm">{{ status.passwordSet ? '修改密码' : '设置密码' }}</button>
          </view>
        </view>
        <view v-if="!status.phoneBound" class="auth-toolbar">
          <text class="auth-caption">请验证手机号，下次登录时可以直接进入。</text>
          <!-- #ifdef H5 -->
          <button v-if="status.wechatAvailable && status.wechatBound" class="auth-alt" :disabled="busy" @click="bind">绑定手机号</button>
          <!-- #endif -->
        </view>
        <view v-if="editing" class="auth-form">
          <input v-if="status.passwordSet" v-model="current" class="auth-input" :password="true" maxlength="72"
            autocomplete="current-password" placeholder="当前密码" aria-label="当前密码" :disabled="busy" />
          <input v-model="password" class="auth-input" :password="true" maxlength="32"
            autocomplete="new-password" placeholder="新密码（8～32 位，含字母和数字）" aria-label="新密码" :disabled="busy" />
          <input v-model="confirmation" class="auth-input" :password="true" maxlength="32"
            autocomplete="new-password" placeholder="再次输入新密码" aria-label="确认密码" :disabled="busy" />
          <button class="auth-primary" :disabled="busy" @click="save">{{ busy ? '保存中…' : '保存密码' }}</button>
          <view class="auth-alternatives">
            <button class="auth-alt" :disabled="busy" @click="clearForm">取消</button>
            <button v-if="status.passwordSet && status.passwordLoginEnabled" class="auth-alt" :disabled="busy" @click="recover">忘记密码</button>
          </view>
        </view>
      </template>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import ScreenHeader from '@/components/ScreenHeader.vue'
import { getAccountSecurity, putAccountPassword, startWechatBinding, completeWechatBinding, removeWechatBinding } from '@/api/user'
import { useUserStore } from '@/store/user'
import { removeWechatTicketFromUrl } from '@/utils/web-login-flow'

const users = useUserStore()
const securityPath = '/pages/mine/security'
const status = ref(null), loading = ref(true), failed = ref(false), busy = ref(false), editing = ref(false)
const current = ref(''), password = ref(''), confirmation = ref('')
const toast = title => uni.showToast({ title, icon: 'none' })
function clearForm() { current.value = ''; password.value = ''; confirmation.value = ''; editing.value = false }
function showForm() { clearForm(); editing.value = true }
async function refresh() {
  loading.value = true
  failed.value = false
  try { status.value = (await getAccountSecurity()).data }
  catch (_) { failed.value = true } finally { loading.value = false }
}
async function save() {
  if (busy.value) return
  if (!/^(?=.*[A-Za-z])(?=.*[0-9]).{8,32}$/.test(password.value)) return toast('密码须为 8～32 位，含字母和数字')
  if (password.value !== confirmation.value) return toast('两次输入的密码不一致')
  if (status.value.passwordSet && !current.value) return toast('请输入当前密码')
  busy.value = true
  try {
    const { data } = await putAccountPassword({ currentPassword: current.value, password: password.value, confirmation: confirmation.value })
    users.acceptWebLogin(data)
    clearForm()
    await refresh()
    toast('密码已保存')
  } catch (_) {} finally { busy.value = false }
}
async function bind() {
  if (busy.value) return
  // #ifdef H5
  if (!/MicroMessenger/i.test(navigator.userAgent)) return toast('请在微信内打开后绑定')
  busy.value = true
  try {
    const { data } = await startWechatBinding()
    window.location.assign(data.authorizeUrl)
  } catch (_) {} finally { busy.value = false }
  // #endif
}
function recover() { uni.navigateTo({ url: '/pages/login/index?panel=reset&redirect=%2Fpages%2Fmine%2Fsecurity' }) }
function askUnbind() {
  if (busy.value) return
  if (!status.value.phoneBound) return toast('请先绑定手机号')
  uni.showModal({
    title: '解绑微信',
    content: '解绑后可继续用手机号登录；使用此微信接收的公众号通知也会停止。',
    confirmText: '解绑',
    success: async ({ confirm }) => {
      if (!confirm || busy.value) return
      busy.value = true
      try { await removeWechatBinding(); await refresh(); toast('微信已解绑') }
      catch (_) {} finally { busy.value = false }
    }
  })
}
onLoad(async options => {
  if (!users.isLoggedIn) {
    uni.redirectTo({ url: '/pages/login/index?redirect=%2Fpages%2Fmine%2Fsecurity' })
    return
  }
  if (options?.wechat_error === '1') toast('微信授权未完成，请重新绑定')
  if (/^[a-f0-9]{32}$/.test(options?.wechat_ticket || '')) {
    removeWechatTicketFromUrl()
    try {
      const { data } = await completeWechatBinding({ ticket: options.wechat_ticket })
      if (data?.bindTicket) {
        users.beginWechatPhoneBinding(data.bindTicket, securityPath, {
          accountBinding: true, takeover: Boolean(data.maskedPhone), phone: data.maskedPhone
        })
        uni.reLaunch({ url: '/pages/login/bind-phone' })
        return
      }
      toast('微信已绑定')
    } catch (_) {}
  }
  await refresh()
})
</script>

<style scoped>
.security-shell { align-items:start; padding-top:0 }
.security-card { padding:0 16px; background:var(--auth-field); border-radius:12px }
.security-row { display:flex; align-items:center; justify-content:space-between; gap:12px; min-height:64px; font-size:15px; padding:8px 0; border-bottom:1px solid var(--auth-border); box-sizing:border-box }
.security-row:last-child { border-bottom:0 }
.security-summary { display:block; color:var(--auth-muted); font-size:12px; margin-top:4px }
</style>

<style lang="scss">
@import '@/styles/auth-flow.scss';
</style>
