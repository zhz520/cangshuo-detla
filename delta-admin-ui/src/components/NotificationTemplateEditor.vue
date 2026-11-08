<template>
  <section v-if="guide" class="template-editor">
    <div class="editor-heading">
      <div>
        <strong>{{ guide.roleLabel }} · {{ guide.channel === 'wechat' ? '公众号模板' : '短信模板' }}</strong>
        <p>按事件直接填写，保存时自动生成后台使用的 JSON，无需手工编辑。</p>
      </div>
      <el-tag size="small" effect="plain">{{ guide.channel === 'wechat' ? '服务号' : '阿里云' }}</el-tag>
    </div>

    <el-alert v-if="parseError" type="warning" :closable="false" show-icon :title="parseError" />
    <el-alert v-if="validationErrors.length" type="error" :closable="false" show-icon title="请先修正模板配置">
      <template #default><ul class="validation-list"><li v-for="error in validationErrors" :key="error">{{ error }}</li></ul></template>
    </el-alert>

    <article v-for="event in guide.events" :key="event.key" class="event-editor">
      <div class="event-heading">
        <div><strong>{{ event.label }}</strong><code>{{ event.key }}</code></div>
        <el-button link type="primary" @click="recommend(event)">使用推荐字段</el-button>
      </div>
      <p class="event-trigger">{{ event.trigger }}</p>

      <div class="primary-fields">
        <label>
          <span>{{ guide.channel === 'wechat' ? '微信模板 ID' : '阿里云模板 Code' }}</span>
          <el-input v-model="form[event.key].template" maxlength="128" :placeholder="guide.channel === 'wechat' ? '从公众号“我的模板”详情复制' : '例如 SMS_123456789'" @input="commit" />
        </label>
        <label v-if="guide.channel === 'wechat'">
          <span>消息点击链接 <small>选填</small></span>
          <el-input v-model="form[event.key].url" maxlength="2000" placeholder="https://当前客户域名/跳转页面" @input="commit" />
        </label>
      </div>

      <details v-if="guide.channel === 'wechat'" class="mini-program-settings">
        <summary>小程序跳转（选填，填写后优先于网页链接）</summary>
        <div class="primary-fields two">
          <label><span>小程序 AppID</span><el-input v-model="form[event.key].miniAppId" maxlength="64" placeholder="wx..." @input="commit" /></label>
          <label><span>页面路径</span><el-input v-model="form[event.key].miniPagepath" maxlength="200" placeholder="pages/order/detail?id=..." @input="commit" /></label>
        </div>
      </details>

      <div class="field-heading"><span>模板参数</span><small>左侧是模板变量，右侧是系统数据源</small></div>
      <div v-for="(field, index) in form[event.key].fields" :key="index" class="field-row">
        <div class="field-name">
          <el-input v-model="field.key" maxlength="64" :placeholder="guide.channel === 'wechat' ? '例如 thing1' : '例如 orderNo'" @input="commit" />
          <small>{{ fieldHint(field.key) }}</small>
        </div>
        <el-select v-model="field.source" placeholder="选择系统数据" @change="commit">
          <el-option v-for="source in guide.sources" :key="source.source" :label="`${source.label}（${source.source}）`" :value="source.source" />
        </el-select>
        <el-button text type="danger" :disabled="form[event.key].fields.length <= 1" @click="removeField(event.key, index)">删除</el-button>
      </div>
      <el-button plain size="small" @click="addField(event.key)">添加模板参数</el-button>

      <details class="platform-preview">
        <summary>查看官方模板正文示例</summary>
        <pre>{{ event.content }}</pre>
        <button type="button" class="copy-link" @click="copy(event.content)">复制模板正文</button>
      </details>
      <div class="final-example"><span>最终通知示例</span><p>{{ event.finalExample }}</p></div>
    </article>

    <details class="generated-json">
      <summary>查看自动生成的 JSON</summary>
      <pre>{{ generatedJson }}</pre>
      <el-button link type="primary" @click="copy(generatedJson)">复制 JSON</el-button>
    </details>
  </section>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { notificationConfigGuide } from '@/utils/notification-examples'

const props = defineProps({
  modelValue: { type: String, default: '{}' },
  configKey: { type: String, required: true }
})
const emit = defineEmits(['update:modelValue', 'validity'])
const guide = computed(() => notificationConfigGuide(props.configKey))
const parseError = ref('')
const form = reactive({})
let extraGroups = {}
let emitting = false

function blankEvent() {
  return { template: '', url: '', miniAppId: '', miniPagepath: '', fields: [{ key: '', source: '' }] }
}

function hydrate() {
  const raw = String(props.modelValue || '{}')
  let parsed = {}
  parseError.value = ''
  try {
    parsed = raw.trim() ? JSON.parse(raw) : {}
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('模板配置必须是对象')
  } catch (error) {
    parsed = {}
    parseError.value = `原有 JSON 无法解析，请重新填写：${error.message}`
  }
  const known = new Set((guide.value?.events || []).map(event => event.key))
  extraGroups = Object.fromEntries(Object.entries(parsed).filter(([key]) => !known.has(key)))
  for (const event of guide.value?.events || []) {
    const source = parsed[event.key] || {}
    const fields = source.fields && typeof source.fields === 'object' && !Array.isArray(source.fields)
      ? Object.entries(source.fields).map(([key, value]) => ({ key, source: String(value || '') }))
      : []
    form[event.key] = {
      template: String(source.templateId || source.templateCode || ''),
      url: String(source.url || ''),
      miniAppId: String(source.miniprogram?.appid || ''),
      miniPagepath: String(source.miniprogram?.pagepath || ''),
      fields: fields.length ? fields : [{ key: '', source: '' }]
    }
  }
}

function serialize() {
  const result = { ...extraGroups }
  for (const event of guide.value?.events || []) {
    const value = form[event.key]
    if (!value) continue
    const fields = Object.fromEntries(value.fields.filter(field => field.key.trim() && field.source).map(field => [field.key.trim(), field.source]))
    if (!value.template.trim() && !Object.keys(fields).length && !value.url.trim()) continue
    const item = guide.value.channel === 'wechat'
      ? { templateId: value.template.trim(), fields }
      : { templateCode: value.template.trim(), fields }
    if (guide.value.channel === 'wechat') {
      if (value.url.trim()) item.url = value.url.trim()
      if (value.miniAppId.trim() || value.miniPagepath.trim()) item.miniprogram = { appid: value.miniAppId.trim(), pagepath: value.miniPagepath.trim() }
    }
    result[event.key] = item
  }
  return result
}

const generatedJson = computed(() => JSON.stringify(serialize(), null, 2))
const validationErrors = computed(() => {
  const errors = []
  if (parseError.value) errors.push(parseError.value)
  for (const event of guide.value?.events || []) {
    const value = form[event.key]
    if (!value) continue
    const rows = value.fields.filter(field => field.key.trim() || field.source)
    const configured = Boolean(value.template.trim() || value.url.trim() || value.miniAppId.trim() || value.miniPagepath.trim() || rows.length)
    if (!configured) continue
    if (!value.template.trim()) errors.push(`${event.label}：请填写${guide.value.channel === 'wechat' ? '微信模板 ID' : '阿里云模板 Code'}`)
    if (!rows.length) errors.push(`${event.label}：至少添加一个模板参数`)
    if (rows.length > 20) errors.push(`${event.label}：单个事件最多配置 20 个参数`)
    const keys = new Set()
    for (const field of rows) {
      const key = field.key.trim()
      if (!/^[A-Za-z_]+[0-9]*$/.test(key)) errors.push(`${event.label}：参数名“${key || '空'}”格式不正确`)
      if (guide.value.channel === 'wechat' && key && !guide.value.wechatFieldTypes.some(type => key.startsWith(type.prefix))) errors.push(`${event.label}：${key} 不是当前微信官方支持的字段类型`)
      if (!field.source) errors.push(`${event.label}：参数 ${key || '(未填写)'} 未选择系统数据`)
      if (keys.has(key)) errors.push(`${event.label}：参数名 ${key} 重复`)
      keys.add(key)
    }
    if (value.url.trim()) {
      try {
        const url = new URL(value.url.trim())
        if (!['http:', 'https:'].includes(url.protocol) || !url.hostname) throw new Error()
      } catch { errors.push(`${event.label}：消息点击链接不是有效的 HTTP/HTTPS 地址`) }
    }
    if (Boolean(value.miniAppId.trim()) !== Boolean(value.miniPagepath.trim())) errors.push(`${event.label}：小程序 AppID 和页面路径需要同时填写`)
    if (value.miniAppId.trim() && !/^[A-Za-z0-9_-]{2,64}$/.test(value.miniAppId.trim())) errors.push(`${event.label}：小程序 AppID 格式不正确`)
  }
  return errors
})

function commit() {
  emitting = true
  emit('update:modelValue', generatedJson.value)
}

function addField(eventKey) {
  form[eventKey].fields.push({ key: '', source: '' })
}

function removeField(eventKey, index) {
  if (form[eventKey].fields.length <= 1) return
  form[eventKey].fields.splice(index, 1)
  commit()
}

function recommend(event) {
  form[event.key].fields = Object.entries(event.fields || {}).map(([key, source]) => ({ key, source }))
  commit()
}

function fieldHint(key) {
  const name = String(key || '').trim()
  if (guide.value?.channel !== 'wechat') return name ? '须与阿里云模板变量完全一致' : ''
  const type = guide.value.wechatFieldTypes.find(item => name.startsWith(item.prefix))
  if (!type) return name ? '请核对是否为微信模板当前支持的字段类型' : ''
  return `${type.label}，最多 ${type.max} 个字符`
}

async function copy(text) {
  try { await navigator.clipboard.writeText(text) }
  catch {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    textarea.remove()
  }
  ElMessage.success('已复制')
}

watch([() => props.modelValue, () => props.configKey], () => {
  if (emitting) { emitting = false; return }
  hydrate()
}, { immediate: true })
watch(validationErrors, errors => emit('validity', errors.length === 0), { immediate: true })
</script>

<style scoped>
.template-editor { width:100%; margin-top:10px; padding:14px; border:1px solid #dfe6ef; border-radius:13px; background:#fff; }
.editor-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; margin-bottom:12px; }
.editor-heading strong { color:#1d2733; font-size:15px; }
.editor-heading p { margin:5px 0 0; color:#7c8795; font-size:12px; line-height:1.55; }
.event-editor { margin-top:12px; padding:14px; border:1px solid #e7ebf0; border-radius:11px; background:#fafbfd; }
.event-heading { display:flex; align-items:center; justify-content:space-between; gap:10px; }
.event-heading > div { display:flex; align-items:center; gap:8px; }
.event-heading strong { color:#26303b; font-size:14px; }
.event-heading code { padding:2px 6px; border-radius:5px; background:#edf1f6; color:#6d7886; font-size:11px; }
.event-trigger { margin:7px 0 12px; color:#7b8795; font-size:11px; line-height:1.6; }
.primary-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; }
.primary-fields.two { margin-top:10px; }
.primary-fields label { display:grid; gap:6px; color:#596575; font-size:11px; }
.primary-fields small { color:#9ba4af; }
.mini-program-settings { margin-top:10px; }
.mini-program-settings summary, .platform-preview summary, .generated-json summary { cursor:pointer; color:#667386; font-size:11px; }
.field-heading { display:flex; align-items:baseline; justify-content:space-between; gap:10px; margin:15px 0 8px; color:#45515f; font-size:12px; font-weight:650; }
.field-heading small { color:#99a2ad; font-size:10px; font-weight:400; }
.field-row { display:grid; grid-template-columns:minmax(120px,.9fr) minmax(180px,1.6fr) auto; gap:8px; margin-bottom:8px; }
.field-name { display:grid; gap:3px; }
.field-name small { min-height:14px; color:#98a2ad; font-size:9px; line-height:1.35; }
.validation-list { margin:6px 0 0; padding-left:18px; line-height:1.65; }
.platform-preview { margin-top:12px; }
.platform-preview pre, .generated-json pre { margin:8px 0; padding:10px; border:1px solid #e5e9ef; border-radius:8px; background:#fff; color:#334155; font-size:11px; line-height:1.65; white-space:pre-wrap; overflow-wrap:anywhere; }
.copy-link { padding:0; border:0; background:transparent; color:#4f6f9d; font-size:11px; cursor:pointer; }
.final-example { margin-top:11px; padding:10px 12px; border-radius:8px; background:#eef4fb; }
.final-example span { color:#7c8795; font-size:10px; }
.final-example p { margin:4px 0 0; color:#334155; font-size:12px; line-height:1.65; }
.generated-json { margin-top:14px; padding-top:12px; border-top:1px solid #edf0f4; }
@media(max-width:760px) {
  .template-editor { padding:11px; }
  .primary-fields { grid-template-columns:1fr; }
  .field-row { grid-template-columns:1fr auto; }
  .field-row .el-input { grid-column:1 / -1; }
  .event-editor { padding:11px; }
}
</style>
