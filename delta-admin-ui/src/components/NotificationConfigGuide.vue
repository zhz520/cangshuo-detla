<template>
  <details v-if="guide" class="notification-guide">
    <summary>
      查看配置教程、字段说明与五类通知内容示例
      <span class="guide-role">{{ guide.roleLabel }} · {{ guide.provider }}</span>
    </summary>

    <div class="guide-body">
      <div class="guide-intro">
        <strong>{{ guide.roleLabel }}通知模板</strong>
        <span>{{ guide.roleDescription }}</span>
      </div>

      <section class="guide-section">
        <h4>1. 先理解字段：左侧是模板变量，右侧是系统数据</h4>
        <div class="field-table-wrap">
          <table class="guide-table field-table">
            <thead><tr><th>右侧数据源</th><th>作用</th><th>示例值</th><th>使用注意</th></tr></thead>
            <tbody>
              <tr v-for="source in guide.sources" :key="source.source">
                <td data-label="系统数据源"><code>{{ source.source }}</code></td>
                <td data-label="作用">{{ source.label }}</td>
                <td data-label="示例值">{{ source.example }}</td>
                <td data-label="使用注意">{{ source.note }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="guide-tip">公众号建议采用结构化的“订单号、状态、金额、时间、客户、打手”字段；短信受审核模板限制，优先使用模板正文明确列出的变量，不必强行放全所有字段。</p>
      </section>

      <section class="guide-section">
        <h4>2. {{ guide.provider }}怎么配置</h4>
        <ol class="provider-steps">
          <li v-for="step in guide.providerSteps" :key="step">{{ step }}</li>
        </ol>
        <div class="key-explain">
          <div v-if="guide.channel === 'wechat'">
            <code>templateId</code>
            <span>填微信公众号“我的模板”详情里的模板 ID。字段映射示例：<code>&quot;thing1&quot;: &quot;message&quot;</code> 表示把系统正文写入模板的 <code v-text="'{{thing1.DATA}}'"></code>。</span>
          </div>
          <div v-else>
            <code>templateCode</code>
            <span>填阿里云短信模板管理审核通过的 Code，例如 <code>SMS_123456789</code>。字段映射示例：<code>&quot;orderNo&quot;: &quot;orderNo&quot;</code> 对应正文里的 <code>${orderNo}</code>。</span>
          </div>
        </div>
        <el-alert :title="guide.channel === 'wechat' ? '关于公众号跳转链接' : '关于短信变量名'" :description="guide.linkHelp" type="warning" :closable="false" show-icon />
      </section>

      <section class="guide-section">
        <h4>3. 五类事件的平台模板与最终通知预览</h4>
        <p class="guide-tip">以下只是虚构数据示例。实际内容、订单号和金额会按业务事件替换；接收资格仍受角色、后台权限、客服上下班状态和公众号绑定状态控制。</p>
        <article v-for="event in guide.events" :key="event.key" class="event-card">
          <div class="event-head">
            <div><strong>{{ event.label }}</strong><code>{{ event.key }}</code></div>
            <span>最终通知示例</span>
          </div>
          <p class="event-trigger">{{ event.trigger }}</p>
          <div class="final-preview">{{ event.finalExample }}</div>
          <details class="platform-content">
            <summary>查看{{ guide.channel === 'wechat' ? '公众号模板正文' : '阿里云短信模板内容' }}示例</summary>
            <pre>{{ event.content }}</pre>
            <el-button link type="primary" @click="copy(event.content, `content-${event.key}`)">复制{{ guide.channel === 'wechat' ? '模板正文' : '短信模板内容' }}</el-button>
          </details>
        </article>
      </section>

      <section class="guide-section">
        <div class="json-title">
          <h4>4. 完整 JSON 配置示例</h4>
          <el-button link type="primary" @click="copy(example, 'json')">复制完整 JSON</el-button>
        </div>
        <pre class="json-example">{{ example }}</pre>
        <p class="guide-tip">模板 ID/Code 和左侧字段名必须替换为当前平台审核通过的模板值；右侧系统数据源保持不变。</p>
      </section>
    </div>
  </details>
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { notificationConfigExample, notificationConfigGuide } from '@/utils/notification-examples'

const props = defineProps({
  configKey: { type: String, required: true }
})

const guide = computed(() => notificationConfigGuide(props.configKey))
const example = computed(() => notificationConfigExample(props.configKey))

async function copy(text, key) {
  try {
    await navigator.clipboard.writeText(text)
  } catch (_) {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    textarea.remove()
  }
  ElMessage.success(key === 'json' ? '完整 JSON 已复制' : '模板内容已复制')
}
</script>

<style scoped>
.notification-guide {
  width: 100%;
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid #dfe5ec;
  border-radius: 10px;
  background: #f8fafc;
}
.notification-guide > summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
  color: #1f2937;
  font-size: 13px;
  font-weight: 650;
}
.guide-role {
  flex: none;
  padding: 3px 8px;
  border-radius: 999px;
  background: #e8eef8;
  color: #52627a;
  font-size: 11px;
  font-weight: 500;
}
.guide-body { margin-top: 14px; }
.guide-intro {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border-radius: 8px;
  background: #fff;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}
.guide-intro strong { color: #1f2937; font-size: 14px; }
.guide-section { margin-top: 18px; }
.guide-section h4 { margin: 0 0 10px; color: #263548; font-size: 13px; }
.field-table-wrap { overflow-x: auto; border: 1px solid #e3e8ef; border-radius: 8px; background: #fff; }
.guide-table { width: 100%; min-width: 650px; border-collapse: collapse; font-size: 12px; line-height: 1.55; }
.guide-table th, .guide-table td { padding: 9px 10px; border-bottom: 1px solid #edf0f4; text-align: left; vertical-align: top; }
.guide-table th { background: #f3f6fa; color: #52627a; font-weight: 600; white-space: nowrap; }
.guide-table tr:last-child td { border-bottom: 0; }
.guide-table td:first-child { white-space: nowrap; }
.guide-tip { margin: 8px 0 0; color: #667085; font-size: 12px; line-height: 1.65; }
.provider-steps { margin: 0; padding-left: 22px; color: #344054; font-size: 12px; line-height: 1.8; }
.provider-steps li + li { margin-top: 4px; }
.key-explain { margin: 12px 0; padding: 10px 12px; border-left: 3px solid #667eea; background: #fff; color: #667085; font-size: 12px; line-height: 1.65; }
.key-explain > div { display: grid; gap: 4px; }
.key-explain code { color: #334155; }
.event-card { margin-top: 10px; padding: 12px; border: 1px solid #e3e8ef; border-radius: 8px; background: #fff; }
.event-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.event-head > div { display: flex; align-items: center; gap: 8px; }
.event-head code { padding: 2px 5px; border-radius: 4px; background: #eef2f7; color: #667085; font-size: 11px; }
.event-head > span { color: #98a2b3; font-size: 11px; }
.event-trigger { margin: 7px 0 9px; color: #667085; font-size: 12px; line-height: 1.6; }
.final-preview { padding: 10px 12px; border-radius: 7px; background: #f3f6fa; color: #273449; font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.platform-content { margin-top: 9px; }
.platform-content summary { cursor: pointer; color: #52627a; font-size: 11px; }
.platform-content pre, .json-example { margin: 8px 0 4px; padding: 10px 12px; overflow: auto; border: 1px solid #e4e9f0; border-radius: 7px; background: #fbfcfe; color: #273449; font-size: 11px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.json-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.json-title h4 { margin-bottom: 0; }
@media (max-width: 760px) {
  .notification-guide { padding: 11px 12px; }
  .notification-guide > summary { align-items: flex-start; flex-direction: column; gap: 6px; }
  .event-head { align-items: flex-start; flex-direction: column; gap: 6px; }
  .provider-steps { padding-left: 18px; }
  .field-table-wrap { overflow:visible; border:0; background:transparent; }
  .guide-table { display:block; min-width:0; border-collapse:separate; border-spacing:0 8px; }
  .guide-table thead { display:none; }
  .guide-table tbody { display:grid; gap:8px; }
  .guide-table tr { display:grid; gap:7px; padding:11px 12px; border:1px solid #e3e8ef; border-radius:9px; background:#fff; }
  .guide-table td { display:grid; grid-template-columns:72px minmax(0,1fr); gap:8px; padding:0; border:0; overflow-wrap:anywhere; }
  .guide-table td::before { content:attr(data-label); color:#98a2b3; font-size:10px; font-weight:600; }
  .guide-table td:first-child { white-space:normal; }
}
</style>
