<template>
  <section class="binding-page">
    <header class="binding-header">
      <div>
        <span class="eyebrow">微信通知</span>
        <h2>公众号消息通知</h2>
        <p>当前后台账号单独绑定一个微信，用于接收订单、退款、投诉和审核通知。</p>
      </div>
      <el-tag :type="status.bound ? 'success' : status.enabled ? 'warning' : 'info'" effect="plain">
        {{ status.bound ? '已绑定' : status.enabled ? '待绑定' : '暂不可绑定' }}
      </el-tag>
    </header>

    <div class="status-card">
      <div class="identity">
        <StaffAvatar :size="48" :thumbnail-width="160" />
        <div>
          <strong>{{ userStore.nickname || '工作人员' }}</strong>
          <span>{{ roleLabel }} · {{ status.bound ? '微信通知已启用' : '等待绑定微信' }}</span>
        </div>
      </div>
      <div class="binding-state">
        <span>绑定规则</span>
        <strong>一个微信只能绑定一个账号</strong>
      </div>
    </div>

    <el-alert v-if="!status.licensed" title="公众号通知功能尚未开通" type="warning" :closable="false" show-icon>
      <template #default>请管理员在「功能与更新 → 功能商城」开通并启用公众号模板通知。</template>
    </el-alert>
    <el-alert v-else-if="!status.enabled" title="公众号通知尚未启用" type="warning" :closable="false" show-icon>
      <template #default>请管理员在「系统管理 → 系统配置 → 公众号通知」完成公众号和模板配置。</template>
    </el-alert>
    <el-alert v-else-if="status.templateReady === false" title="当前角色模板尚未配置完整" type="warning" :closable="false" show-icon>
      <template #default>绑定不受影响；配置完成前，缺少的 {{ status.missingTemplateGroups?.length || 0 }} 类通知会记录为模板未配置。</template>
    </el-alert>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <div class="binding-guide">
      <span class="guide-mark">{{ status.bound ? '✓' : 'i' }}</span>
      <div>
        <strong>{{ status.bound ? '此账号已绑定当前微信' : '绑定只需要两步' }}</strong>
        <p v-if="status.bound">需要更换微信时，先解除当前绑定，再由同一后台账号重新绑定。</p>
        <p v-else>先关注平台服务号，然后用手机微信打开本页面并登录同一账号，点击下方按钮完成授权。</p>
      </div>
    </div>

    <footer class="binding-actions">
      <el-button v-if="status.enabled && !status.bound && wechatEnvironment" type="primary" size="large" :loading="busy" @click="bind">
        绑定当前微信
      </el-button>
      <el-button v-else-if="status.enabled && !status.bound" type="primary" size="large" :loading="busy" @click="copyAddress">
        复制微信打开地址
      </el-button>
      <el-button v-if="status.bound" plain size="large" :loading="busy" @click="unbind">解除绑定</el-button>
      <el-button size="large" :loading="loading" @click="load">刷新状态</el-button>
    </footer>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import StaffAvatar from '@/components/StaffAvatar.vue'
import { completeOfficialWechatBinding, getOfficialWechatBinding, removeOfficialWechatBinding, startOfficialWechatBinding } from '@/api/official-wechat'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const status = reactive({ licensed: false, enabled: false, bound: false, templateReady: true, missingTemplateGroups: [] })
const wechatEnvironment = /MicroMessenger/i.test(navigator.userAgent)
const roleLabel = computed(() => userStore.role === 'admin' ? '管理员' : '客服')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await getOfficialWechatBinding()
    Object.assign(status, { licensed: false, enabled: false, bound: false, templateReady: true, missingTemplateGroups: [] }, data || {})
  } catch (err) {
    error.value = err?.message || '公众号绑定状态暂时无法读取'
  } finally {
    loading.value = false
  }
}

async function completeCallback() {
  const code = typeof route.query.code === 'string' ? route.query.code : ''
  const state = typeof route.query.state === 'string' ? route.query.state : ''
  if (!code || !state) return
  busy.value = true
  try {
    await completeOfficialWechatBinding({ code, state })
    ElMessage.success('公众号消息通知绑定成功')
  } catch (err) {
    error.value = err?.message || '公众号绑定未完成，请重新操作'
  } finally {
    busy.value = false
    await router.replace({ path: route.path, query: {}, hash: '' })
    await load()
  }
}

async function bind() {
  if (!wechatEnvironment) {
    await copyAddress()
    return
  }
  busy.value = true
  error.value = ''
  try {
    const { data } = await startOfficialWechatBinding()
    if (!data || !/^https:\/\/open\.weixin\.qq\.com\//i.test(data)) throw new Error('微信授权地址无效')
    window.location.assign(data)
  } catch (err) {
    error.value = err?.message || '暂时无法发起微信绑定'
    busy.value = false
  }
}

async function copyAddress() {
  const target = new URL(window.location.href)
  target.search = ''
  target.hash = ''
  const address = target.toString()
  busy.value = true
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(address)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = address
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      textarea.remove()
    }
    ElMessage.success('地址已复制，请发送到手机微信打开')
  } catch (_) {
    ElMessage.warning('复制失败，请手动复制浏览器地址')
  } finally {
    busy.value = false
  }
}

async function unbind() {
  try {
    await ElMessageBox.confirm('解除后当前账号将停止接收公众号业务通知。', '解除公众号绑定', { type: 'warning' })
  } catch { return }
  busy.value = true
  error.value = ''
  try {
    await removeOfficialWechatBinding()
    ElMessage.success('已解除公众号绑定')
    await load()
  } catch (err) {
    error.value = err?.message || '解除绑定失败，请稍后重试'
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  await load()
  await completeCallback()
})
</script>

<style scoped>
.binding-page { display:grid; gap:16px; width:min(760px,100%); margin:0 auto; }
.binding-header { display:flex; align-items:flex-start; justify-content:space-between; gap:18px; padding:24px; border:1px solid #e5e8ed; border-radius:18px; background:linear-gradient(135deg,#fff 0%,#f4f7fc 100%); }
.eyebrow { color:#7b8798; font-size:10px; font-weight:750; letter-spacing:.14em; }
.binding-header h2 { margin:7px 0 6px; color:#1d2229; font-size:24px; }
.binding-header p { max-width:560px; margin:0; color:#6d7682; font-size:13px; line-height:1.7; }
.status-card { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:20px; padding:20px 22px; border:1px solid #e7eaee; border-radius:16px; background:#fff; }
.identity { display:flex; align-items:center; gap:13px; min-width:0; }
.identity strong,.identity span,.binding-state span,.binding-state strong { display:block; }
.identity strong { color:#20252c; font-size:15px; }
.identity span,.binding-state span { margin-top:4px; color:#87909c; font-size:11px; }
.binding-state { min-width:190px; padding-left:20px; border-left:1px solid #edf0f3; text-align:right; }
.binding-state strong { margin-top:5px; color:#2d5d42; font-size:13px; }
.binding-guide { display:flex; gap:13px; padding:18px 20px; border:1px solid #e7eaee; border-radius:16px; background:#fff; }
.guide-mark { display:grid; place-items:center; width:30px; height:30px; flex:none; border-radius:10px; background:#eef2f8; color:#3f536d; font-size:13px; font-weight:800; }
.binding-guide strong { color:#30363e; font-size:14px; }
.binding-guide p { margin:5px 0 0; color:#78818d; font-size:12px; line-height:1.7; }
.binding-actions { display:flex; flex-wrap:wrap; gap:10px; padding-top:2px; }
@media(max-width:768px) {
  .binding-page { gap:12px; }
  .binding-header { padding:18px; border-radius:15px; }
  .binding-header h2 { font-size:21px; }
  .status-card { grid-template-columns:1fr; gap:14px; padding:17px; }
  .binding-state { min-width:0; padding:13px 0 0; border-top:1px solid #edf0f3; border-left:0; text-align:left; }
  .binding-guide { padding:16px; }
  .binding-actions { position:sticky; bottom:calc(62px + var(--app-safe-bottom)); z-index:2; padding:10px; border:1px solid #e7eaee; border-radius:14px; background:rgba(255,255,255,.96); box-shadow:0 8px 28px rgba(32,42,58,.08); }
  .binding-actions :deep(.el-button) { flex:1; min-height:44px; margin:0; }
}
</style>
