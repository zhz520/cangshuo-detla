<template>
  <section class="menu-editor" v-loading="busy">
    <div class="menu-header">
      <div class="menu-title">
        <strong>公众号菜单</strong>
        <el-tag size="small" :type="status === 'PUBLISHED' && !dirty ? 'success' : 'info'">
          {{ dirty ? '有未保存改动' : status === 'PUBLISHED' ? '已发布' : '草稿' }}
        </el-tag>
      </div>
      <div class="menu-actions">
        <el-button :disabled="busy" @click="importMenu">从微信导入</el-button>
        <el-button :disabled="busy || !ready || !dirty" @click="saveMenu">保存草稿</el-button>
        <el-button type="primary" :disabled="busy || !ready || !roots.length || dirty || status === 'PUBLISHED'" @click="publishMenu">发布菜单</el-button>
      </div>
    </div>
    <el-alert v-if="loadFailed" title="菜单读取失败" type="error" :closable="false">
      <template #default><el-button link @click="loadMenu">重新读取</el-button></template>
    </el-alert>
    <div v-else class="menu-workspace">
      <div v-if="ready" class="menu-panel">
        <div class="panel-title"><strong>菜单结构</strong><el-button link type="primary" :disabled="roots.length >= 3" @click="addRoot">添加一级菜单</el-button></div>
        <p v-if="!roots.length" class="muted">还没有菜单。添加一级菜单，或导入公众号已有菜单。</p>
        <ol v-else class="menu-list" aria-label="一级菜单">
          <li v-for="root in roots" :key="root.id">
            <button type="button" class="menu-item" :class="{selected:root.id === activeId}" :aria-pressed="root.id === activeId" @click="select(root)">
              <span>{{ root.name || '未命名菜单' }}</span><small v-if="root.children.length">{{ root.children.length }} 项</small>
            </button>
            <ol v-if="root.children.length" class="menu-list children" :aria-label="`${root.name || '未命名菜单'}的子菜单`">
              <li v-for="child in root.children" :key="child.id">
                <button type="button" class="menu-item" :class="{selected:child.id === activeId}" :aria-pressed="child.id === activeId" @click="select(child)">
                  <span>{{ child.name || '未命名子菜单' }}</span>
                </button>
              </li>
            </ol>
          </li>
        </ol>
      </div>
      <div v-if="ready" class="menu-panel">
        <div class="panel-title"><strong>菜单设置</strong></div>
        <p v-if="!active" class="muted">选择一个菜单后编辑。</p>
        <template v-else>
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="菜单名称">
              <el-input v-model="active.name" />
              <span class="muted">{{ utf8Size(active.name.trim()) }} / {{ menuLimit(activeParent ? 1 : 0) }} 字节</span>
            </el-form-item>
            <template v-if="active.children.length">
              <p class="muted">点击此菜单时展开子菜单。</p>
            </template>
            <template v-else>
              <el-form-item label="点击动作">
                <el-select v-model="active.type" placeholder="选择动作">
                  <el-option v-for="item in menuTypes" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </el-form-item>
              <el-form-item v-for="field in menuType(active.type)?.fields || []" :key="field" :label="menuFieldLabels[field]">
                <el-input v-if="field !== 'media_id' && field !== 'article_id'" v-model="active[field]" />
                <el-select v-else-if="field === 'media_id'" v-model="active[field]" filterable allow-create default-first-option placeholder="选择本号素材">
                  <el-option v-for="item in visibleMaterials" :key="item.mediaId" :value="item.mediaId" :label="item.name || '未命名素材'" />
                </el-select>
                <el-input v-else v-model="active[field]" />
              </el-form-item>
            </template>
          </el-form>
          <ul v-if="activeIssues.length" class="menu-errors" role="alert"><li v-for="entry in activeIssues" :key="entry">{{ entry }}</li></ul>
          <div class="menu-actions row-actions">
            <el-button v-if="!activeParent" :disabled="active.children.length >= 5" @click="addChild">添加子菜单</el-button>
            <el-button :disabled="activeIndex <= 0" @click="move(-1)">上移</el-button>
            <el-button :disabled="activeIndex >= siblings.length - 1" @click="move(1)">下移</el-button>
            <el-button type="danger" text @click="removeActive">移除</el-button>
          </div>
        </template>
      </div>
      <div v-if="ready" class="menu-panel preview">
        <div class="panel-title"><strong>手机预览</strong></div>
        <div class="preview-phone">
          <div class="preview-title">{{ accountName || '微信公众号' }}</div>
          <div class="preview-body"><span v-if="previewText">{{ previewText }}</span></div>
          <div v-if="previewRoot?.children.length" class="preview-sub">
            <button v-for="child in previewRoot.children" :key="child.id" type="button" @click="preview(child)">
              {{ child.name || '未命名子菜单' }}
            </button>
          </div>
          <div class="preview-nav">
            <button v-for="root in roots" :key="root.id" type="button" :aria-expanded="root.children.length ? previewRoot?.id === root.id : undefined" @click="preview(root)">
              {{ root.name || '未命名菜单' }}
            </button>
            <span v-if="!roots.length" class="muted">菜单将显示在这里</span>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getWechatMenu, getWechatMaterials, saveWechatMenu, importWechatMenu, publishWechatMenu } from '@/api/wechat-operations'
import { menuTypes, menuType, menuFieldLabels, utf8Size, menuLimit, newMenuButton, readMenuButtons, writeMenuButtons, menuErrors, moveMenuButton } from './menu-model'

const props = defineProps({ accountId: { type: String, required: true }, accountName: String })
const emit = defineEmits(['dirty'])
const roots = ref([]), saved = ref(''), status = ref('DRAFT'), activeId = ref(null)
const previewRoot = ref(null), previewText = ref(''), ready = ref(false), loadFailed = ref(false), busy = ref(false)
const media = ref([])
const wire = computed(() => writeMenuButtons(roots.value))
const dirty = computed(() => ready.value && saved.value !== JSON.stringify(wire.value))
const activeParent = computed(() => roots.value.find(root => root.children.some(child => child.id === activeId.value)))
const siblings = computed(() => activeParent.value?.children || roots.value)
const activeIndex = computed(() => siblings.value.findIndex(item => item.id === activeId.value))
const active = computed(() => siblings.value[activeIndex.value])
const errors = computed(() => menuErrors(roots.value))
const activeIssues = computed(() => errors.value.get(activeId.value) || [])
const visibleMaterials = computed(() => active.value?.type === 'view_limited' ? media.value.filter(item => item.type === 'news') : media.value)
watch(dirty, value => emit('dirty', value), { flush: 'sync' })

function select(node) { activeId.value = node?.id ?? null }
function addRoot() {
  if (roots.value.length >= 3) return
  const draft = newMenuButton()
  roots.value.push(draft)
  select(draft)
}
function addChild() {
  if (activeParent.value || !active.value || active.value.children.length >= 5) return
  const child = newMenuButton()
  active.value.children.push(child)
  select(child)
}
function move(step) { moveMenuButton(siblings.value, activeIndex.value, step) }
async function removeActive() {
  const target = active.value
  if (!target || busy.value) return
  if (target.children.length) {
    try { await ElMessageBox.confirm('同时移除该菜单下的全部子菜单？', '移除菜单', { type: 'warning' }) }
    catch { return }
  }
  if (active.value !== target) return
  const parent = activeParent.value
  const list = siblings.value
  const index = activeIndex.value
  list.splice(index, 1)
  if (previewRoot.value?.id === target.id) previewRoot.value = null
  select(list[index] || list[index - 1] || parent)
}
function preview(node) {
  if (node.children.length) {
    previewRoot.value = previewRoot.value?.id === node.id ? null : node
    previewText.value = ''
  } else {
    previewRoot.value = null
    previewText.value = menuType(node.type)?.label || '未配置动作'
  }
}
function readBack(data) {
  const rootIndex = roots.value.findIndex(item => item.id === (activeParent.value?.id || activeId.value))
  const wasChild = Boolean(activeParent.value), childIndex = activeIndex.value
  roots.value = readMenuButtons(data?.buttons)
  saved.value = JSON.stringify(wire.value)
  status.value = data?.status || 'DRAFT'
  ready.value = true
  emit('dirty', false)
  previewRoot.value = null
  const root = roots.value[rootIndex] || roots.value[0]
  select(wasChild ? root?.children[childIndex] || root : root)
}
async function loadMenu() {
  busy.value = true
  loadFailed.value = false
  try { readBack((await getWechatMenu(props.accountId)).data) }
  catch (error) { loadFailed.value = true; ElMessage.error(error?.message || '菜单读取失败') }
  finally { busy.value = false }
}
async function confirm(message, title) {
  try { await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: title }); return true }
  catch { return false }
}
async function importMenu() {
  if (!await confirm('导入会替换当前本地草稿和未保存改动，不会修改微信线上菜单。', '确认导入')) return
  busy.value = true
  try {
    readBack((await importWechatMenu(props.accountId)).data)
    loadFailed.value = false
    ElMessage.success('已导入微信菜单')
  } catch (error) { ElMessage.error(error?.message || '菜单导入失败') }
  finally { busy.value = false }
}
async function saveMenu() {
  if (errors.value.size) {
    const [id, issues] = errors.value.entries().next().value
    if (id != null) activeId.value = id
    return ElMessage.warning(issues[0])
  }
  busy.value = true
  try { readBack((await saveWechatMenu(props.accountId, { buttons: wire.value })).data); ElMessage.success('菜单草稿已保存') }
  catch (error) { ElMessage.error(error?.message || '菜单保存失败') }
  finally { busy.value = false }
}
async function publishMenu() {
  if (busy.value || dirty.value || !ready.value || !roots.value.length) return
  const snapshot = JSON.parse(saved.value)
  if (!await confirm(`将覆盖「${props.accountName || '当前公众号'}」的线上普通菜单。`, '确认发布')) return
  busy.value = true
  try { readBack((await publishWechatMenu(props.accountId, { buttons: snapshot })).data); ElMessage.success('公众号菜单已发布') }
  catch (error) { ElMessage.error(error?.message || '菜单发布失败') }
  finally { busy.value = false }
}
watch(() => active.value?.type, async type => {
  if (!['media_id', 'view_limited'].includes(type) || media.value.length) return
  const results = await Promise.allSettled(['image', 'news', 'voice', 'video'].map(kind => getWechatMaterials(props.accountId, kind)))
  media.value = results.flatMap(result => result.status === 'fulfilled' ? result.value.data || [] : [])
})
onMounted(loadMenu)
</script>

<style scoped>
.menu-editor{display:grid;gap:14px;padding-top:10px}
.menu-header,.panel-title,.menu-actions,.menu-title{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.menu-header,.panel-title{justify-content:space-between}
.menu-actions :deep(.el-button){margin-left:0}
.menu-workspace{display:grid;grid-template-columns:minmax(160px,1fr) minmax(250px,2fr) minmax(200px,1.2fr);gap:12px;align-items:start}
.menu-panel{min-width:0;border:1px solid var(--el-border-color-light);border-radius:12px;padding:12px}
.panel-title{min-height:36px;margin-bottom:8px}
.menu-list,.menu-errors{list-style:none;margin:0;padding:0;display:grid;gap:5px}
.menu-list.children{margin:4px 0 6px 12px;padding-left:8px;border-left:1px solid var(--el-border-color-light)}
.menu-item,.preview-phone button{display:flex;align-items:center;justify-content:space-between;width:100%;gap:4px;text-align:left;overflow-wrap:anywhere;border:1px solid var(--el-border-color-light);border-radius:6px;color:var(--el-text-color-primary);background:var(--el-bg-color);padding:6px 8px;min-height:44px;cursor:pointer;font:inherit}
.menu-item.selected,.menu-item:hover,.preview-phone button:hover{border-color:var(--el-color-primary);background:var(--el-fill-color-light)}
.menu-item:focus-visible,.preview-phone button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}
.menu-item small,.muted{color:var(--el-text-color-secondary);font-size:12px}
.row-actions{margin-top:12px}.menu-errors{color:var(--el-color-danger);font-size:13px}
.preview-phone{border:1px solid var(--el-border-color-light);border-radius:14px;overflow:hidden;background:var(--el-fill-color-extra-light)}
.preview-title,.preview-nav{background:var(--el-bg-color);padding:8px;overflow-wrap:anywhere;text-align:center}
.preview-title{font-weight:600;border-bottom:1px solid var(--el-border-color-light)}
.preview-body{min-height:140px;padding:10px;color:var(--el-text-color-secondary)}
.preview-nav,.preview-sub{display:flex;gap:4px;flex-wrap:wrap}
.preview-nav{border-top:1px solid var(--el-border-color-light)}
.preview-sub{padding:0 8px 8px}
.preview-nav button,.preview-sub button{flex:1 1 0;min-width:0;justify-content:center;text-align:center}
@media(max-width:1180px){.menu-workspace{grid-template-columns:minmax(0,1fr) minmax(0,1.6fr)}.preview{grid-column:1/-1}}
@media(max-width:660px){.menu-workspace{grid-template-columns:minmax(0,1fr)}}
</style>
