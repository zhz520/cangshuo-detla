<template>
  <div class="login-container">
    <canvas ref="canvasRef" class="particle-canvas" aria-hidden="true" />
    <div class="login-box">
      <h2 class="login-title">{{ siteStore.siteName }}</h2>
      <p class="login-subtitle">管理员与客服统一登录</p>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent="handleLogin">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" aria-label="用户名" autocomplete="username" prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" aria-label="密码" autocomplete="current-password" prefix-icon="Lock" show-password />
        </el-form-item>
        <el-form-item prop="captchaCode">
          <div class="captcha-row">
            <el-input v-model="form.captchaCode" maxlength="6" placeholder="图形验证码" aria-label="图形验证码" autocomplete="one-time-code" prefix-icon="Key" />
            <button type="button" class="captcha-image" :disabled="captchaLoading" aria-label="刷新图形验证码" title="点击刷新验证码" @click="refreshCaptcha">
              <img v-if="captchaImage" :src="captchaImage" alt="图形验证码" />
              <span v-else>{{ captchaLoading ? '加载中' : '点击刷新' }}</span>
            </button>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button class="login-btn" native-type="submit" :loading="loading">登 录</el-button>
        </el-form-item>
      </el-form>
      <p class="login-switch">
        <a v-if="!isH5" href="/h5/login">手机端入口</a>
        <a v-else href="/">电脑版</a>
      </p>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, reactive, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useSiteStore } from '@/stores/site'
import { getLoginCaptcha } from '@/api/auth'
import { h5Path, isH5Path } from '@/utils/h5-route'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const isH5 = computed(() => isH5Path(route.path))
const userStore = useUserStore()
const siteStore = useSiteStore()

const formRef = ref(null)
const loading = ref(false)
const captchaLoading = ref(false)
const captchaImage = ref('')
const form = reactive({ username: '', password: '', captchaId: '', captchaCode: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }, { pattern: /^[0-9]{6}$/, message: '请输入6位数字验证码', trigger: 'blur' }]
}

async function refreshCaptcha() {
  if (captchaLoading.value) return
  captchaLoading.value = true
  try {
    const result = await getLoginCaptcha()
    form.captchaId = result.data?.captchaId || ''
    captchaImage.value = result.data?.image || ''
  } catch {
    captchaImage.value = ''
  } finally {
    form.captchaCode = ''
    captchaLoading.value = false
  }
}

async function handleLogin() {
  if (loading.value) return
  loading.value = true
  try {
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
    await userStore.login(form)
    ElMessage.success('登录成功')
    const redirect = route.query.redirect
    const target = typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : (isH5.value ? '/h5/dashboard' : '/')
    if (isH5.value) await router.replace(isH5Path(target) ? target : h5Path(target))
    else await router.push(target)
  } catch (e) {
    // error handled by request interceptor
    await refreshCaptcha()
  } finally {
    loading.value = false
  }
}

/* ---- 金色粒子动画 ---- */
const canvasRef = ref(null)
let animId = 0
let stopParticles = () => {}
class Particle {
  constructor(w, h) { this.reset(w, h, true) }
  reset(w, h, init = false) {
    this.x = Math.random() * w
    this.y = init ? Math.random() * h : h + 10
    this.r = Math.random() * 2 + 0.5
    this.vx = (Math.random() - 0.5) * 0.6
    this.vy = -(Math.random() * 0.8 + 0.3)
    this.life = 1
    this.decay = Math.random() * 0.006 + 0.002
    const gold = [212, 175, 55]
    const bright = [201, 164, 74]
    const t = Math.random()
    this.r_ = Math.round(gold[0] * t + bright[0] * (1 - t))
    this.g_ = Math.round(gold[1] * t + bright[1] * (1 - t))
    this.b_ = Math.round(gold[2] * t + bright[2] * (1 - t))
  }
  update() { this.x += this.vx; this.y += this.vy; this.life -= this.decay }
  draw(ctx) {
    ctx.globalAlpha = this.life * 0.8
    ctx.fillStyle = `rgb(${this.r_},${this.g_},${this.b_})`
    ctx.beginPath(); ctx.arc(this.x, this.y, this.r, 0, Math.PI * 2); ctx.fill()
  }
}
onMounted(() => {
  refreshCaptcha()
  const canvas = canvasRef.value; if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
  let w, h, particles = []
  let lastFrame = 0
  function draw(advance = false) {
    ctx.clearRect(0, 0, w, h)
    for (const p of particles) {
      if (advance) p.update()
      p.draw(ctx)
      if (p.life <= 0) p.reset(w, h)
    }
  }
  const resizeCanvas = () => {
    w = canvas.width = canvas.clientWidth
    h = canvas.height = canvas.clientHeight
    particles = Array.from({ length: Math.min(100, Math.max(18, Math.round(w * h / 18000))) }, () => new Particle(w, h))
    draw()
  }
  function loop(time) {
    if (time - lastFrame >= 1000 / 30) { draw(true); lastFrame = time }
    animId = requestAnimationFrame(loop)
  }
  function updatePlayback() {
    cancelAnimationFrame(animId)
    animId = 0
    if (!motion.matches && !document.hidden) { lastFrame = 0; animId = requestAnimationFrame(loop) }
  }
  resizeCanvas()
  window.addEventListener('resize', resizeCanvas, { passive: true })
  document.addEventListener('visibilitychange', updatePlayback)
  if (motion.addEventListener) motion.addEventListener('change', updatePlayback)
  else motion.addListener(updatePlayback)
  updatePlayback()
  stopParticles = () => {
    cancelAnimationFrame(animId)
    window.removeEventListener('resize', resizeCanvas)
    document.removeEventListener('visibilitychange', updatePlayback)
    if (motion.removeEventListener) motion.removeEventListener('change', updatePlayback)
    else motion.removeListener(updatePlayback)
  }
})
onUnmounted(() => stopParticles())
</script>

<style lang="scss" scoped>
.login-container {
  width: 100%;
  min-height: var(--app-viewport-height);
  padding: calc(24px + var(--app-safe-top)) calc(16px + var(--app-safe-right)) calc(24px + var(--app-safe-bottom)) calc(16px + var(--app-safe-left));
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(ellipse at 50% 20%, #ffffff 0%, #f5f6f7 62%, #eceef0 100%);
  position: relative;
  overflow: auto;
}
.particle-canvas {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 0;
  opacity: 0.06;
  filter: grayscale(1);
}
.login-box {
  position: relative;
  z-index: 1;
  width: 420px;
  max-width: 100%;
  flex-shrink: 0;
  padding: 44px 40px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid #e6e7e9;
  border-radius: 16px;
  box-shadow: 0 16px 56px rgba(23, 25, 28, 0.06);
  backdrop-filter: blur(12px);
}
.login-title {
  text-align: center;
  font-size: 26px;
  font-weight: 800;
  color: #17191c;
  margin-bottom: 4px;
  letter-spacing: 2px;
}
.login-subtitle {
  text-align: center;
  color: #73777e;
  margin-bottom: 32px;
  font-size: 14px;
}
.login-btn {
  width: 100%;
  background: #17191c;
  border: none;
  color: #ffffff;
  font-weight: bold;
  font-size: 16px;
  letter-spacing: 4px;
  border-radius: 999px;
  &:hover { background: #30343a; color: #ffffff; }
}
.login-switch {
  margin: 18px 0 0;
  text-align: center;
  font-size: 13px;
}
.login-switch a {
  color: #5f6670;
  text-decoration: none;
}
.login-switch a:hover {
  color: #17191c;
  text-decoration: underline;
}
.captcha-row {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 132px;
  gap: 10px;
}
.captcha-image {
  height: 40px;
  padding: 0;
  overflow: hidden;
  border: 1px solid #e6e7e9;
  border-radius: 8px;
  background: #f5f6f8;
  color: #73777e;
  font-size: 12px;
  cursor: pointer;
  img { display: block; width: 100%; height: 100%; object-fit: cover; }
  &:disabled { cursor: wait; opacity: .7; }
}
:deep(.el-input__wrapper) {
  background: #fafafb;
  border: 1px solid #e6e7e9;
  box-shadow: none !important;
  &:hover { border-color: #b9bdc4; }
  &.is-focus { border-color: #17191c; }
}
:deep(.el-input__inner) {
  color: #17191c;
  &::placeholder { color: #73777e; }
}
:deep(.el-input__prefix .el-icon) { color: #73777e; }
:deep(.el-form-item__error) { color: #c94f59; }
@media (max-width: 480px) {
  .login-box { padding: 28px 22px; }
  .login-title { font-size: 24px; overflow-wrap: anywhere; word-break: break-word; }
}
@media (max-width: 360px) {
  .captcha-row { grid-template-columns: 1fr; gap: 8px; }
  .captcha-image { width: 100%; height: 44px; }
}
@media (max-height: 500px) {
  .login-container { padding-top: calc(12px + var(--app-safe-top)); padding-bottom: calc(12px + var(--app-safe-bottom)); }
  .login-box { padding-top: 20px; padding-bottom: 20px; }
  .login-subtitle { margin-bottom: 16px; }
}
</style>
