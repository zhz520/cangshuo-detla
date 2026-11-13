<template>
  <div class="theme-store">
    <el-card shadow="never">
      <div class="page-heading">
        <div>
          <h2>主题商店</h2>
          <p>主题由授权中心统一销售。购买并启用后，客户 H5 与打手 H5 会使用对应主题；授权失效时自动回退默认主题。</p>
        </div>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </el-card>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
    />

    <div class="theme-grid">
      <el-card
        v-for="theme in themes"
        :key="theme.id"
        shadow="never"
        class="theme-card"
        :class="{ active: theme.active }"
      >
        <div class="theme-preview" :class="'preview-' + theme.id">
          <div class="preview-sidebar" />
          <div class="preview-content">
            <div class="preview-top" />
            <div class="preview-row">
              <div class="preview-block large" />
              <div class="preview-block" />
              <div class="preview-block" />
            </div>
            <div class="preview-bottom">
              <div class="preview-line wide" />
              <div class="preview-line" />
            </div>
          </div>
        </div>

        <div class="theme-title-row">
          <h3>{{ theme.name }}</h3>
          <el-tag v-if="theme.active" type="success" effect="plain">当前使用</el-tag>
          <el-tag v-else-if="theme.id === 'default'" type="info" effect="plain">基础</el-tag>
          <el-tag v-else-if="theme.entitled" type="warning" effect="plain">已购买</el-tag>
          <el-tag v-else type="info" effect="plain">未购买</el-tag>
        </div>
        <p class="theme-description">{{ theme.description }}</p>
        <p class="theme-status">{{ statusText(theme) }}</p>

        <div class="theme-actions">
          <el-button
            v-if="theme.id !== 'default' && !theme.entitled"
            type="primary"
            :loading="buying === theme.id"
            @click="buy(theme)"
          >
            购买并开通
          </el-button>
          <el-button
            v-else-if="theme.id !== 'default' && theme.entitled && !theme.enabled"
            type="primary"
            plain
            @click="$router.push('/platform/features')"
          >
            去功能商城启用
          </el-button>
          <el-button
            v-else-if="!theme.active"
            type="primary"
            :loading="changing === theme.id"
            @click="activate(theme)"
          >
            启用此主题
          </el-button>
          <el-button v-else disabled>正在使用</el-button>
          <el-button
            v-if="theme.id !== 'default' && theme.entitled"
            link
            type="primary"
            @click="$router.push('/platform/features')"
          >
            查看授权与有效期
          </el-button>
        </div>
      </el-card>
    </div>

    <el-card shadow="never" class="hint-card">
      <h3>主题范围</h3>
      <p>首版主题覆盖客户 H5、打手 H5 和管理后台的网页端。支付、登录、退款、客服和异常页保留清晰可读的基础状态样式。</p>
      <p>主题不会改变业务数据、订单价格、权限和域名归属。客户可随时切回默认主题。</p>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createPlatformCheckout,
  getThemeSettings,
  setActiveTheme
} from '@/api/platform'

const themes = ref([])
const activeTheme = ref('default')
const loading = ref(false)
const buying = ref('')
const changing = ref('')
const error = ref('')

function statusText(theme) {
  if (theme.active) return '当前站点正在使用此主题。'
  if (theme.id === 'default') return '无需购买，随时可切回。'
  if (theme.entitled && !theme.enabled) return '已获得授权，请在功能商城启用此模块。'
  if (theme.entitled) return '已获得授权，可以直接启用。'
  return theme.reason || '请在授权中心购买此主题。'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await getThemeSettings()
    activeTheme.value = response.data?.activeTheme || 'default'
    themes.value = Array.isArray(response.data?.themes) ? response.data.themes : []
  } catch (requestError) {
    error.value = requestError?.message || '主题设置暂时无法读取'
  } finally {
    loading.value = false
  }
}

async function activate(theme) {
  changing.value = theme.id
  try {
    const response = await setActiveTheme(theme.id)
    activeTheme.value = response.data?.activeTheme || 'default'
    themes.value = Array.isArray(response.data?.themes) ? response.data.themes : themes.value
    ElMessage.success(theme.id === 'default' ? '已恢复默认主题' : '主题已启用，客户 H5 刷新后生效')
  } catch (requestError) {
    if (requestError?.message) ElMessage.error(requestError.message)
  } finally {
    changing.value = ''
  }
}

async function buy(theme) {
  buying.value = theme.id
  try {
    const response = await createPlatformCheckout('theme_neumorphism')
    const checkoutUrl = response.data?.checkoutUrl
    if (!checkoutUrl) throw new Error('授权中心未返回支付地址')
    const checkout = new URL(checkoutUrl)
    if (checkout.protocol !== 'https:' && !(checkout.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(checkout.hostname))) {
      throw new Error('授权中心支付地址无效')
    }
    window.location.assign(checkout.href)
  } catch (requestError) {
    if (requestError?.message) ElMessage.error(requestError.message)
  } finally {
    buying.value = ''
  }
}

onMounted(load)
</script>

<style scoped>
.theme-store { display: grid; gap: 18px; }
.page-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; flex-wrap: wrap; }
.page-heading h2, .page-heading p { margin: 0; }
.page-heading h2 { font-size: 20px; }
.page-heading p { margin-top: 8px; color: #73777e; line-height: 1.7; max-width: 760px; }
.theme-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr)); gap: 18px; }
.theme-card { border: 1px solid #e7e9ed; }
.theme-card.active { border-color: #17191c; box-shadow: 0 10px 30px rgba(23, 25, 28, 0.08); }
.theme-card :deep(.el-card__body) { height: 100%; display: flex; flex-direction: column; box-sizing: border-box; }
.theme-preview { height: 170px; border-radius: 14px; overflow: hidden; display: flex; background: #f7f8f9; border: 1px solid #eceef1; }
.preview-sidebar { width: 52px; background: #1d232b; }
.preview-content { flex: 1; padding: 16px; }
.preview-top { height: 12px; width: 45%; border-radius: 8px; background: #dce0e5; }
.preview-row { display: flex; gap: 8px; margin-top: 18px; }
.preview-block { flex: 1; height: 46px; border-radius: 10px; background: #fff; border: 1px solid #e6e8eb; }
.preview-block.large { flex: 1.5; }
.preview-bottom { margin-top: 18px; display: grid; gap: 8px; }
.preview-line { height: 8px; width: 62%; border-radius: 8px; background: #e2e5e9; }
.preview-line.wide { width: 82%; }
.preview-neumorphism { background: #e9eef5; border-color: #e9eef5; }
.preview-neumorphism .preview-sidebar { background: #e9eef5; box-shadow: inset 6px 0 12px #c9d3e2; }
.preview-neumorphism .preview-block,
.preview-neumorphism .preview-top,
.preview-neumorphism .preview-line { background: #edf1f7; border: 0; box-shadow: 5px 5px 10px rgba(150, 163, 183, 0.35), -5px -5px 10px #ffffff; }
.preview-neumorphism .preview-line.wide { background: linear-gradient(90deg, #6888da, #4b70c9); box-shadow: 4px 4px 9px rgba(120, 140, 180, 0.3); }
.preview-neumorphism .preview-content { background: #e9eef5; }
.theme-title-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 18px; }
.theme-title-row h3 { margin: 0; font-size: 17px; }
.theme-description { color: #555b63; line-height: 1.7; min-height: 48px; }
.theme-status { color: #73777e; font-size: 13px; line-height: 1.6; min-height: 42px; }
.theme-actions { margin-top: auto; padding-top: 14px; display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.hint-card h3 { margin: 0 0 10px; }
.hint-card p { color: #555b63; line-height: 1.8; margin: 6px 0; }
@media (max-width: 640px) {
  .page-heading { flex-direction: column; }
  .theme-preview { height: 145px; }
}
</style>
