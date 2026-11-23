<template>
  <section class="wechat-tools">
    <PageHeader title="公众号运营工具" description="菜单、自动回复、渠道二维码和粉丝同步；所有数据只属于当前租户公众号。">
      <template #actions><el-button @click="loadAccounts">刷新</el-button></template>
    </PageHeader>
    <el-alert type="warning" :closable="false" show-icon
      title="公众号粉丝不是电竞账号。粉丝数据只用于公众号运营，不会自动获得订单、钱包或后台权限。" />

    <el-card shadow="never">
      <div class="account-toolbar">
        <div><strong>运营公众号</strong></div>
        <el-select :model-value="selectedId" placeholder="选择公众号" style="width:320px" @change="changeAccount">
          <el-option v-for="item in accounts" :key="item.id" :label="`${item.name} · ${item.appId}`" :value="item.id" />
        </el-select>
      </div>
    </el-card>

    <el-empty v-if="!selectedId" description="请先在公众号运营中新增并选择公众号" />
    <el-tabs v-else v-model="active" class="tool-tabs" @tab-change="loadTab">
      <el-tab-pane label="菜单" name="menu">
        <WechatMenuEditor :key="selectedId" :account-id="selectedId" :account-name="selectedAccount?.name" @dirty="menuDirty = $event" />
      </el-tab-pane>

      <el-tab-pane label="自动回复" name="replies">
        <div class="panel-head"><div><strong>自动回复规则</strong><span v-if="managedReplies">开放平台仅支持文字回复。</span></div><el-button type="primary" @click="openRule()">新增规则</el-button></div>
        <DataTable :loading="busy" :data="rules" row-key="id" empty-text="还没有自动回复规则">
          <el-table-column label="类型" width="110"><template #default="{ row }">{{ ruleTypeLabel(row.ruleType) }}</template></el-table-column>
          <el-table-column prop="keyword" label="关键词" min-width="160" />
          <el-table-column label="匹配" width="100"><template #default="{ row }">{{ row.matchMode==='EXACT'?'完全匹配':'包含' }}</template></el-table-column>
          <el-table-column label="回复" min-width="260"><template #default="{ row }"><el-tag size="small">{{ replyTypeLabel(row.replyType) }}</el-tag><span class="content-preview">{{ row.content || row.mediaId || '-' }}</span></template></el-table-column>
          <el-table-column prop="priority" label="优先级" width="90" />
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.enabled?'success':'info'" size="small">{{ row.enabled?'启用':'停用' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="140" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openRule(row)">编辑</el-button><el-button link type="danger" @click="removeRule(row)">删除</el-button></template></el-table-column>
        </DataTable>
      </el-tab-pane>

      <el-tab-pane label="渠道二维码" name="qrcodes">
        <div class="panel-head"><div><strong>渠道二维码</strong><span>临时二维码会过期；永久二维码用于长期渠道。扫码统计以微信侧事件为准。</span></div><el-button type="primary" @click="openQr">创建二维码</el-button></div>
        <DataTable :loading="busy" :data="qrs" row-key="id" empty-text="还没有渠道二维码">
          <el-table-column prop="name" label="渠道" min-width="160" />
          <el-table-column prop="scene" label="场景值" min-width="150" />
          <el-table-column label="类型" width="100"><template #default="{ row }">{{ row.type==='PERMANENT'?'永久':'临时' }}</template></el-table-column>
          <el-table-column prop="scans" label="扫码数" width="90" />
          <el-table-column label="二维码" width="100"><template #default="{ row }"><a v-if="row.imageUrl" :href="row.imageUrl" target="_blank" rel="noopener noreferrer">查看</a><span v-else>-</span></template></el-table-column>
          <el-table-column prop="createdAt" label="创建时间" min-width="170" />
          <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="danger" @click="removeQr(row)">删除</el-button></template></el-table-column>
        </DataTable>
      </el-tab-pane>

      <el-tab-pane label="粉丝" name="fans">
        <div class="panel-head"><div><strong>公众号粉丝</strong><span>每次最多同步 500 人，OpenID 脱敏展示。</span></div><el-button type="primary" :loading="busy" @click="syncFans">同步粉丝</el-button></div>
        <DataTable :loading="busy" :data="fans" row-key="openid" empty-text="还没有同步粉丝">
          <el-table-column prop="openid" label="OpenID" min-width="180" />
          <el-table-column prop="nickname" label="昵称" min-width="160" />
          <el-table-column label="关注" width="90"><template #default="{ row }"><el-tag :type="row.subscribed?'success':'info'" size="small">{{ row.subscribed?'已关注':'已取关' }}</el-tag></template></el-table-column>
          <el-table-column label="标签" min-width="180"><template #default="{ row }"><el-tag v-for="tag in row.tags" :key="tag" size="small" class="tag-gap">{{ tag }}</el-tag><span v-if="!row.tags?.length">-</span></template></el-table-column>
          <el-table-column prop="lastSyncAt" label="最近同步" min-width="170" />
        </DataTable>
      </el-tab-pane>
      <el-tab-pane label="标签" name="tags">
        <div class="panel-head"><div><strong>粉丝标签</strong><span>标签创建、编辑和删除会同步到微信公众号。</span></div><div class="panel-actions"><el-button @click="syncTags">同步标签</el-button><el-button type="primary" @click="openTag()">新增标签</el-button></div></div>
        <DataTable :loading="busy" :data="tags" row-key="tagId" empty-text="还没有标签"><el-table-column prop="name" label="标签名称" min-width="220"/><el-table-column prop="fanCount" label="粉丝数" width="110"/><el-table-column label="操作" width="140" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openTag(row)">编辑</el-button><el-button link type="danger" @click="removeTag(row)">删除</el-button></template></el-table-column></DataTable>
      </el-tab-pane>
      <el-tab-pane label="素材" name="materials">
        <div class="panel-head"><div><strong>公众号素材</strong><span>同步微信已有素材或从后台上传素材。</span></div><div class="panel-actions"><el-select v-model="materialType" style="width:130px"><el-option label="图片" value="image"/><el-option label="语音" value="voice"/><el-option label="视频" value="video"/><el-option label="图文" value="news"/></el-select><el-upload :show-file-list="false" :http-request="uploadMaterial"><el-button type="primary">上传素材</el-button></el-upload><el-button type="primary" @click="syncMaterials">同步素材</el-button></div></div>
        <DataTable :loading="busy" :data="materials" row-key="mediaId" empty-text="还没有同步素材"><el-table-column prop="name" label="名称" min-width="220"/><el-table-column prop="type" label="类型" width="100"/><el-table-column prop="mediaId" label="Media ID" min-width="240" show-overflow-tooltip/><el-table-column prop="updateTime" label="更新时间" min-width="170"/><el-table-column label="链接" width="80"><template #default="{ row }"><a v-if="row.url" :href="row.url" target="_blank" rel="noopener noreferrer">查看</a><span v-else>-</span></template></el-table-column></DataTable>
      </el-tab-pane>
      <el-tab-pane label="统计" name="stats">
        <div class="panel-head"><div><strong>公众号运营统计</strong><span>粉丝、消息、二维码和素材的本地同步口径。</span></div><el-button @click="loadStats">刷新</el-button></div>
        <div class="stats-grid"><article v-for="item in statCards" :key="item.label"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></article></div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="ruleDialog" :title="ruleForm.id?'编辑自动回复':'新增自动回复'" width="min(620px,94vw)" destroy-on-close>
      <el-form label-position="top" class="dialog-form">
        <div class="form-grid three">
          <el-form-item label="规则类型"><el-select v-model="ruleForm.ruleType"><el-option v-for="(label,value) in ruleTypes" :key="value" :label="label" :value="value" /></el-select></el-form-item>
          <el-form-item label="匹配方式"><el-select v-model="ruleForm.matchMode" :disabled="ruleForm.ruleType!=='KEYWORD'"><el-option label="包含关键词" value="CONTAINS" /><el-option label="完全匹配" value="EXACT" /></el-select></el-form-item>
          <el-form-item label="优先级"><el-input-number v-model="ruleForm.priority" :min="-1000" :max="1000" /></el-form-item>
        </div>
        <el-form-item v-if="ruleForm.ruleType==='KEYWORD'" label="关键词"><el-input v-model="ruleForm.keyword" maxlength="80" /></el-form-item>
        <div class="form-grid">
          <el-form-item v-if="!managedReplies" label="回复类型"><el-select v-model="ruleForm.replyType"><el-option v-for="(label,value) in replyTypes" :key="value" :label="label" :value="value" /></el-select></el-form-item>
          <el-form-item label="启用"><el-switch v-model="ruleForm.enabled" /></el-form-item>
        </div>
        <el-form-item v-if="ruleForm.replyType==='TEXT'" label="文字内容"><el-input v-model="ruleForm.content" type="textarea" :rows="4" maxlength="1000" /></el-form-item>
        <el-form-item v-else label="素材 Media ID"><el-input v-model="ruleForm.mediaId" maxlength="128" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="ruleDialog=false">取消</el-button><el-button type="primary" :loading="busy" @click="saveRule">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="qrDialog" title="创建渠道二维码" width="min(520px,94vw)" destroy-on-close>
      <el-form label-position="top" class="dialog-form">
        <el-form-item label="渠道名称"><el-input v-model="qrForm.name" maxlength="80" /></el-form-item>
        <el-form-item label="场景值"><el-input v-model="qrForm.scene" maxlength="64" placeholder="例如 douyin-2026" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="类型"><el-select v-model="qrForm.type"><el-option label="临时二维码" value="TEMP" /><el-option label="永久二维码" value="PERMANENT" /></el-select></el-form-item>
          <el-form-item v-if="qrForm.type==='TEMP'" label="有效期（秒）"><el-input-number v-model="qrForm.expireSeconds" :min="60" :max="2592000" /></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="qrDialog=false">取消</el-button><el-button type="primary" :loading="busy" @click="saveQr">创建</el-button></template>
    </el-dialog>
    <el-dialog v-model="tagDialog" :title="tagForm.tagId?'编辑标签':'新增标签'" width="min(420px,94vw)" destroy-on-close>
      <el-form label-position="top"><el-form-item label="标签名称"><el-input v-model="tagForm.name" maxlength="30"/></el-form-item></el-form>
      <template #footer><el-button @click="tagDialog=false">取消</el-button><el-button type="primary" :loading="busy" @click="saveTag">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import DataTable from '@/components/DataTable.vue'
import WechatMenuEditor from './WechatMenuEditor.vue'
import { getWechatAccounts, getWechatAutoReplies, saveWechatAutoReply, deleteWechatAutoReply, getWechatQrCodes, createWechatQrCode, deleteWechatQrCode, getWechatFans, syncWechatFans, getWechatTags, syncWechatTags, saveWechatTag, deleteWechatTag, getWechatMaterials, syncWechatMaterials, uploadWechatMaterial, getWechatStats } from '@/api/wechat-operations'

const accounts = ref([]), selectedId = ref(''), active = ref('menu'), busy = ref(false)
const menuDirty = ref(false)
const selectedAccount = computed(() => accounts.value.find(account => account.id === selectedId.value))
const rules = ref([]), qrs = ref([]), fans = ref([]), tags = ref([]), materials = ref([]), stats = ref({}), materialType = ref('image'), tagDialog = ref(false)
const tagForm = reactive({ tagId: null, name: '' })
const statCards = computed(() => [{label:'已同步粉丝',value:stats.value.fans||0},{label:'已关注',value:stats.value.subscribed||0},{label:'消息数',value:stats.value.messages||0},{label:'二维码扫码',value:stats.value.qrScans||0},{label:'素材数',value:stats.value.materials||0}])
const ruleDialog = ref(false), qrDialog = ref(false)
let ruleAccountId = ''
const ruleTypes = { KEYWORD: '关键词', SUBSCRIBE: '关注后', DEFAULT: '默认回复' }
const replyTypes = { TEXT: '文字', IMAGE: '图片', VOICE: '语音', VIDEO: '视频' }
const managedReplies = computed(() => selectedAccount.value?.mode === 'OPEN_PLATFORM')
const ruleForm = reactive({ id: null, ruleType: 'KEYWORD', keyword: '', matchMode: 'CONTAINS', replyType: 'TEXT', content: '', mediaId: '', priority: 0, enabled: true })
const qrForm = reactive({ name: '', scene: '', type: 'TEMP', expireSeconds: 604800 })
function ruleTypeLabel(value) { return ruleTypes[value] || value }
function replyTypeLabel(value) { return replyTypes[value] || value }

async function loadAccounts() {
  busy.value = true
  try { const { data } = await getWechatAccounts(); accounts.value = Array.isArray(data) ? data : []; if (!selectedId.value || !accounts.value.some(item => item.id === selectedId.value)) selectedId.value = accounts.value[0]?.id || ''; if (selectedId.value) await loadTab() } catch (error) { ElMessage.error(error?.message || '公众号读取失败') } finally { busy.value = false }
}
async function changeAccount(next) {
  if (next === selectedId.value) return
  if (menuDirty.value) {
    try { await ElMessageBox.confirm('当前菜单有未保存改动。放弃改动并切换公众号？', '切换公众号', { type: 'warning' }) }
    catch { return }
  }
  selectedId.value = next
  menuDirty.value = false
  await loadTab()
}
async function loadTab() {
  if (!selectedId.value) return
  if (active.value === 'replies') return loadRules()
  if (active.value === 'qrcodes') return loadQrs()
  if (active.value === 'fans') return loadFans()
  if (active.value === 'tags') return loadTags()
  if (active.value === 'materials') return loadMaterials()
  if (active.value === 'stats') return loadStats()
}
watch(selectedId, () => {
  rules.value = []
  ruleDialog.value = false
  ruleAccountId = ''
}, { flush: 'sync' })
async function loadRules() {
  const accountId = selectedId.value
  busy.value = true
  try {
    const { data } = await getWechatAutoReplies(accountId)
    if (selectedId.value === accountId) rules.value = Array.isArray(data) ? data : []
  } catch (error) {
    if (selectedId.value === accountId) ElMessage.error(error?.message || '自动回复读取失败')
  } finally { busy.value = false }
}
function openRule(row = null) {
  ruleAccountId = selectedId.value
  Object.assign(ruleForm, { id: null, ruleType: 'KEYWORD', keyword: '', matchMode: 'CONTAINS', replyType: 'TEXT', content: '', mediaId: '', priority: 0, enabled: true }, row || {})
  ruleDialog.value = true
}
async function saveRule() {
  const accountId = ruleAccountId
  if (!accountId || selectedId.value !== accountId) return
  busy.value = true
  try {
    await saveWechatAutoReply(accountId, ruleForm.id, { ...ruleForm })
    if (selectedId.value !== accountId) return
    ruleDialog.value = false
    ElMessage.success('自动回复已保存')
    await loadRules()
  } catch (error) { ElMessage.error(error?.message || '自动回复保存失败') }
  finally { busy.value = false }
}
async function removeRule(row) {
  const accountId = selectedId.value
  try { await ElMessageBox.confirm(`确认删除“${row.keyword || ruleTypeLabel(row.ruleType)}”规则？`, '删除自动回复', { type: 'warning' }) }
  catch { return }
  if (selectedId.value !== accountId) return
  try {
    await deleteWechatAutoReply(accountId, row.id)
    if (selectedId.value !== accountId) return
    ElMessage.success('规则已删除')
    await loadRules()
  } catch (error) { ElMessage.error(error?.message || '删除失败') }
}
async function loadQrs() { busy.value = true; try { const { data } = await getWechatQrCodes(selectedId.value); qrs.value = Array.isArray(data) ? data : [] } catch (error) { ElMessage.error(error?.message || '二维码读取失败') } finally { busy.value = false } }
function openQr() { Object.assign(qrForm, { name: '', scene: '', type: 'TEMP', expireSeconds: 604800 }); qrDialog.value = true }
async function saveQr() { busy.value = true; try { await createWechatQrCode(selectedId.value, { ...qrForm }); qrDialog.value = false; ElMessage.success('渠道二维码已创建'); await loadQrs() } catch (error) { ElMessage.error(error?.message || '二维码创建失败') } finally { busy.value = false } }
async function removeQr(row) { try { await ElMessageBox.confirm(`确认删除“${row.name}”二维码记录？`, '删除二维码', { type: 'warning' }) } catch { return } try { await deleteWechatQrCode(selectedId.value, row.id); ElMessage.success('二维码记录已删除'); await loadQrs() } catch (error) { ElMessage.error(error?.message || '删除失败') } }
async function loadFans() { busy.value = true; try { const { data } = await getWechatFans(selectedId.value); fans.value = Array.isArray(data) ? data : [] } catch (error) { ElMessage.error(error?.message || '粉丝读取失败') } finally { busy.value = false } }
async function syncFans() { busy.value = true; try { const { data } = await syncWechatFans(selectedId.value); ElMessage.success(`已同步 ${data?.synced || 0} 个粉丝`); await loadFans() } catch (error) { ElMessage.error(error?.message || '粉丝同步失败，请检查公众号用户管理权限') } finally { busy.value = false } }
async function loadTags(){busy.value=true;try{tags.value=(await getWechatTags(selectedId.value)).data||[]}catch(e){ElMessage.error(e?.message||'标签读取失败')}finally{busy.value=false}}
async function syncTags(){busy.value=true;try{tags.value=(await syncWechatTags(selectedId.value)).data||[];ElMessage.success('标签已同步')}catch(e){ElMessage.error(e?.message||'标签同步失败')}finally{busy.value=false}}
function openTag(row=null){Object.assign(tagForm,{tagId:row?.tagId||null,name:row?.name||''});tagDialog.value=true}
async function saveTag(){busy.value=true;try{await saveWechatTag(selectedId.value,tagForm.tagId,{name:tagForm.name});tagDialog.value=false;ElMessage.success('标签已保存');await loadTags()}catch(e){ElMessage.error(e?.message||'标签保存失败')}finally{busy.value=false}}
async function removeTag(row){try{await ElMessageBox.confirm(`确认删除标签“${row.name}”？`,'删除标签',{type:'warning'})}catch{return}try{await deleteWechatTag(selectedId.value,row.tagId);ElMessage.success('标签已删除');await loadTags()}catch(e){ElMessage.error(e?.message||'标签删除失败')}}
async function loadMaterials(){busy.value=true;try{materials.value=(await getWechatMaterials(selectedId.value,materialType.value)).data||[]}catch(e){ElMessage.error(e?.message||'素材读取失败')}finally{busy.value=false}}
async function syncMaterials(){busy.value=true;try{const {data}=await syncWechatMaterials(selectedId.value,materialType.value);ElMessage.success(`已同步 ${data?.synced||0} 个素材`);await loadMaterials()}catch(e){ElMessage.error(e?.message||'素材同步失败')}finally{busy.value=false}}
async function uploadMaterial(options){if(materialType.value==='news')return ElMessage.warning('图文素材请使用草稿接口');busy.value=true;try{await uploadWechatMaterial(selectedId.value,materialType.value,options.file);ElMessage.success('素材已上传');await loadMaterials()}catch(e){ElMessage.error(e?.message||'素材上传失败')}finally{busy.value=false}}
async function loadStats(){try{stats.value=(await getWechatStats(selectedId.value)).data||{}}catch(e){ElMessage.error(e?.message||'统计读取失败')}}
onMounted(loadAccounts)
</script>

<style scoped>
.wechat-tools { display:grid; gap:16px; }.account-toolbar { display:flex; align-items:center; justify-content:space-between; gap:16px; }.account-toolbar strong,.account-toolbar span{display:block}.account-toolbar strong{font-size:14px}.account-toolbar span{margin-top:4px;color:var(--el-text-color-secondary);font-size:12px}.tool-tabs{background:var(--el-bg-color);border:1px solid var(--el-border-color-light);border-radius:14px;padding:0 18px 18px}.panel-head{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:8px 0 14px}.panel-head strong,.panel-head span{display:block}.panel-head strong{font-size:14px}.panel-head span{margin-top:4px;color:var(--el-text-color-secondary);font-size:12px}.panel-actions{display:flex;gap:8px}.code-editor :deep(textarea){font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px;line-height:1.65}.form-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:12px}.muted{color:var(--el-text-color-secondary);font-size:12px}.content-preview{margin-left:8px;color:var(--el-text-color-secondary);font-size:12px}.tag-gap{margin-right:4px}.stats-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px}.stats-grid article{padding:18px;border:1px solid var(--el-border-color-lighter);border-radius:12px;background:var(--el-fill-color-lighter)}.stats-grid span,.stats-grid strong{display:block}.stats-grid span{color:var(--el-text-color-secondary);font-size:12px}.stats-grid strong{margin-top:8px;font-size:26px}.dialog-form{display:grid;gap:2px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.form-grid.three{grid-template-columns:repeat(3,minmax(0,1fr))}@media(max-width:768px){.account-toolbar,.panel-head{align-items:stretch;flex-direction:column}.account-toolbar :deep(.el-select){width:100%!important}.panel-actions{width:100%}.panel-actions :deep(.el-button){flex:1}.form-grid,.form-grid.three{grid-template-columns:1fr}.stats-grid{grid-template-columns:1fr 1fr}}
</style>
