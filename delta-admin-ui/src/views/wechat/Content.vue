<template>
  <section class="wechat-content">
    <PageHeader title="图文与群发" description="图文草稿、群发任务和双人审批；真实群发默认关闭。">
      <template #actions><el-button @click="loadAccounts">刷新</el-button></template>
    </PageHeader>
    <el-alert type="warning" :closable="false" show-icon title="真实群发必须服务端显式启用、任务审批通过，并由另一名管理员执行；模板业务通知不能替代群发。" />
    <el-card shadow="never"><div class="toolbar"><div><strong>运营公众号</strong><span>图文和群发任务只属于当前公众号</span></div><el-select v-model="accountId" placeholder="选择公众号" style="width:320px" @change="loadAll"><el-option v-for="item in accounts" :key="item.id" :label="`${item.name} · ${item.appId}`" :value="item.id" /></el-select></div></el-card>
    <el-empty v-if="!accountId" description="请先绑定公众号" />
    <el-tabs v-else v-model="active" class="content-tabs" @tab-change="loadTab">
      <el-tab-pane label="图文草稿" name="articles">
        <div class="panel-head"><div><strong>图文草稿</strong><span>先保存本地草稿；推送微信草稿和公开发布需后续单独操作。</span></div><el-button type="primary" @click="openArticle()">新增草稿</el-button></div>
        <DataTable :loading="busy" :data="articles" row-key="id" empty-text="还没有图文草稿"><el-table-column prop="title" label="标题" min-width="220"/><el-table-column prop="author" label="作者" width="120"/><el-table-column prop="status" label="状态" width="100"/><el-table-column prop="remoteMediaId" label="微信草稿" min-width="180" show-overflow-tooltip/><el-table-column prop="updatedAt" label="更新时间" min-width="170"/><el-table-column label="操作" width="240" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openArticle(row)">编辑</el-button><el-button v-if="row.status==='DRAFT'" link type="primary" @click="pushArticle(row)">推送草稿</el-button><el-button v-if="row.status==='PUSHED'" link type="success" @click="publishArticle(row)">发布</el-button><el-button link type="danger" @click="removeArticle(row)">删除</el-button></template></el-table-column></DataTable>
      </el-tab-pane>
      <el-tab-pane label="群发审批" name="tasks">
        <div class="panel-head"><div><strong>群发任务</strong><span>提交后由另一名管理员审批；发送前必须开启服务端群发开关。</span></div><el-button type="primary" @click="openTask()">新增任务</el-button></div>
        <DataTable :loading="busy" :data="tasks" row-key="id" empty-text="还没有群发任务"><el-table-column prop="name" label="任务" min-width="180"/><el-table-column prop="targetType" label="范围" width="110"/><el-table-column prop="msgType" label="类型" width="100"/><el-table-column prop="status" label="状态" width="140"/><el-table-column prop="updatedAt" label="更新时间" min-width="170"/><el-table-column label="操作" width="260" fixed="right"><template #default="{ row }"><el-button v-if="['DRAFT','REJECTED'].includes(row.status)" link type="primary" @click="openTask(row)">编辑</el-button><el-button v-if="['DRAFT','REJECTED'].includes(row.status)" link type="primary" @click="act(row,'submit')">提交</el-button><el-button v-if="row.status==='PENDING_APPROVAL'" link type="success" @click="act(row,'approve')">批准</el-button><el-button v-if="row.status==='PENDING_APPROVAL'" link type="danger" @click="act(row,'reject')">驳回</el-button><el-button v-if="row.status==='APPROVED'" link type="danger" @click="send(row)">发送</el-button></template></el-table-column></DataTable>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="articleDialog" :title="articleForm.id ? '编辑图文草稿' : '新增图文草稿'" width="min(800px,96vw)" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="标题"><el-input v-model="articleForm.title" maxlength="120" /></el-form-item>
        <div class="grid">
          <el-form-item label="作者"><el-input v-model="articleForm.author" maxlength="64" /></el-form-item>
          <el-form-item label="封面">
            <el-select v-model="articleForm.coverMediaId" filterable clearable :loading="choicesLoading" placeholder="选择本公众号的图片素材" @change="chooseCover">
              <el-option v-for="item in coverChoices" :key="item.mediaId" :value="item.mediaId" :label="item.name || '未命名图片'" />
            </el-select>
          </el-form-item>
        </div>
        <img v-if="coverSrc" :src="coverSrc" class="cover-preview" alt="封面预览" referrerpolicy="no-referrer" />
        <el-form-item label="摘要"><el-input v-model="articleForm.digest" maxlength="500" /></el-form-item>
        <el-form-item label="正文">
          <RichTextEditor v-model="articleForm.contentHtml" label="图文正文" :show-source="false" :show-image="false" />
        </el-form-item>
        <el-button v-if="bodyImageChoices.length" @click="bodyImageDialog = true">从素材插入图片</el-button>
      </el-form>
      <template #footer>
        <el-button @click="articleDialog=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveArticle">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="bodyImageDialog" append-to-body title="插入正文图片" width="min(480px,94vw)" @closed="bodyImageId = ''">
      <el-form label-position="top">
        <el-form-item label="图片素材">
          <el-select v-model="bodyImageId" filterable placeholder="选择本公众号的图片">
            <el-option v-for="item in bodyImageChoices" :key="item.mediaId" :value="item.mediaId" :label="item.name || '未命名图片'" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="bodyImageDialog=false">取消</el-button><el-button type="primary" :disabled="!bodyImageId" @click="appendBodyImage">插入到正文末尾</el-button></template>
    </el-dialog>

    <el-dialog v-model="taskDialog" :title="taskForm.id ? '编辑群发任务' : '新增群发任务'" width="min(620px,94vw)" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="任务名称"><el-input v-model="taskForm.name" maxlength="80" /></el-form-item>
        <div class="grid">
          <el-form-item label="接收范围">
            <el-select v-model="taskForm.targetType" placeholder="选择接收范围">
              <el-option label="全部粉丝" value="ALL" /><el-option label="按粉丝标签" value="TAG" />
              <el-option label="指定 OpenID（高级）" value="OPENIDS" />
            </el-select>
          </el-form-item>
          <el-form-item label="消息类型">
            <el-select v-model="taskForm.msgType" @change="taskForm.mediaId = ''">
              <el-option label="文字" value="TEXT" /><el-option label="图片" value="IMAGE" /><el-option label="图文" value="MPNEWS" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item v-if="taskForm.targetType==='TAG'" label="粉丝标签">
          <el-select v-model="taskForm.tagId" filterable :loading="choicesLoading" placeholder="选择本公众号的标签">
            <el-option v-for="tag in tagChoices" :key="tag.tagId" :value="tag.tagId" :label="tag.name" />
            <template #empty><p class="choice-empty">暂无标签。请在「运营工具 → 标签」同步或新增。</p></template>
          </el-select>
        </el-form-item>
        <el-form-item v-if="taskForm.targetType==='OPENIDS'" label="指定 OpenID（回车确认一项）">
          <el-select v-model="taskForm.openids" multiple filterable allow-create default-first-option placeholder="输入微信的完整 OpenID" />
        </el-form-item>
        <el-form-item v-if="taskForm.msgType==='TEXT'" label="文字内容">
          <el-input v-model="taskForm.text" type="textarea" :rows="5" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item v-else label="选择素材">
          <el-select v-model="taskForm.mediaId" filterable :loading="choicesLoading" placeholder="选择本公众号已同步的素材">
            <el-option v-for="item in taskMediaChoices" :key="item.mediaId" :value="item.mediaId" :label="item.name || '未命名素材'" />
            <template #empty><p class="choice-empty">暂无该类型素材。请先在「运营工具 → 素材」同步。</p></template>
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="taskDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveTask">保存</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import DataTable from '@/components/DataTable.vue'
import RichTextEditor from '@/components/RichTextEditor.vue'
import { getWechatAccounts, getWechatArticles, saveWechatArticle, deleteWechatArticle, pushWechatArticle, publishWechatArticle, getWechatMassSends, saveWechatMassSend, transitionWechatMassSend, sendWechatMassSend, getWechatMaterials, getWechatTags } from '@/api/wechat-operations'
const accounts=ref([]),accountId=ref(''),active=ref('articles'),articles=ref([]),tasks=ref([]),busy=ref(false),saving=ref(false),articleDialog=ref(false),taskDialog=ref(false)
const articleForm=reactive({id:null,title:'',author:'',digest:'',contentHtml:'',coverUrl:'',coverMediaId:''})
const taskForm=reactive({id:null,name:'',targetType:'',msgType:'TEXT',tagId:null,openids:[],text:'',mediaId:''})
const images=ref([]), news=ref([]), tags=ref([]), choicesLoading=ref(false)
const bodyImageDialog=ref(false), bodyImageId=ref('')
const bodyImageChoices=computed(() => images.value.filter(item => safeImage(item.url)))
const coverSrc=computed(() => safeImage(articleForm.coverUrl))
const coverChoices=computed(() => keepCurrent(images.value, articleForm.coverMediaId))
const taskMediaChoices=computed(() => keepCurrent(taskForm.msgType==='IMAGE' ? images.value : news.value, taskForm.mediaId))
const tagChoices=computed(() => tags.value.some(item => item.tagId===taskForm.tagId) || !taskForm.tagId ? tags.value
  : [{tagId:taskForm.tagId,name:'已选标签（未在当前列表）'}, ...tags.value])
let dialogAccount='', choiceSequence=0, listSequence=0
function safeImage(value) {
  try {
    const url=new URL(value)
    return url.protocol==='https:' && !url.username && !url.password ? url.href : ''
  } catch { return '' }
}
function keepCurrent(items, id) {
  return !id || items.some(item=>item.mediaId===id) ? items : [{mediaId:id,name:'已选素材（未在当前列表）'},...items]
}
function chooseCover(id) { articleForm.coverUrl=images.value.find(item=>item.mediaId===id)?.url || '' }
function appendBodyImage() {
  if (accountId.value!==dialogAccount || !articleDialog.value) return
  const item=bodyImageChoices.value.find(image=>image.mediaId===bodyImageId.value)
  if (!item) return
  const wrapper=document.createElement('p')
  const img=document.createElement('img')
  img.src=safeImage(item.url)
  img.alt=item.name || '文章图片'
  wrapper.append(img)
  articleForm.contentHtml+=wrapper.outerHTML
  bodyImageDialog.value=false
}
watch(accountId, () => {
  choiceSequence++
  choicesLoading.value=false
  dialogAccount=''
  images.value=[]; news.value=[]; tags.value=[]
  articles.value=[]; tasks.value=[]
  articleDialog.value=false; taskDialog.value=false; bodyImageDialog.value=false
}, {flush:'sync'})
async function loadChoices(id, includeTaskOptions=false) {
  const sequence=++choiceSequence
  choicesLoading.value=true
  const jobs=[getWechatMaterials(id,'image')]
  if (includeTaskOptions) jobs.push(getWechatMaterials(id,'news'), getWechatTags(id))
  const result=await Promise.allSettled(jobs)
  if (sequence!==choiceSequence || accountId.value!==id) return
  const options=result.map(item=>item.status==='fulfilled' && Array.isArray(item.value.data) ? item.value.data : [])
  images.value=options[0]
  if(includeTaskOptions){news.value=options[1];tags.value=options[2]}
  if(result.some(item=>item.status==='rejected')) ElMessage.warning('部分素材或标签读取失败，可重新打开编辑窗口')
  choicesLoading.value=false
}
async function loadAccounts(){busy.value=true;try{accounts.value=(await getWechatAccounts()).data||[];if(!accountId.value||!accounts.value.some(x=>x.id===accountId.value))accountId.value=accounts.value[0]?.id||'';await loadTab()}catch(e){ElMessage.error(e?.message||'公众号读取失败')}finally{busy.value=false}}
async function loadAll(){await loadTab()}
async function loadTab(){const id=accountId.value, tab=active.value, sequence=++listSequence;if(!id)return;busy.value=true;try{const result=tab==='articles'?await getWechatArticles(id):await getWechatMassSends(id);if(sequence===listSequence&&accountId.value===id)(tab==='articles'?articles:tasks).value=result.data||[]}catch(e){if(sequence===listSequence&&accountId.value===id)ElMessage.error(e?.message||'内容读取失败')}finally{if(sequence===listSequence)busy.value=false}}
function openArticle(row=null){dialogAccount=accountId.value;Object.assign(articleForm,{id:null,title:'',author:'',digest:'',contentHtml:'',coverUrl:'',coverMediaId:''},row?{id:row.id,title:row.title,author:row.author||'',digest:row.digest||'',contentHtml:row.contentHtml||'',coverUrl:row.coverUrl||'',coverMediaId:row.coverMediaId||''}:{});articleDialog.value=true;void loadChoices(dialogAccount)}
async function saveArticle(){const id=dialogAccount;if(!id||accountId.value!==id)return;if(!articleForm.title.trim())return ElMessage.warning('请填写标题');saving.value=true;try{await saveWechatArticle(id,articleForm.id,{...articleForm});if(accountId.value!==id)return;articleDialog.value=false;ElMessage.success('图文草稿已保存');await loadTab()}catch(e){ElMessage.error(e?.message||'保存失败')}finally{saving.value=false}}
async function confirmedAction(action, message, title, success, failure) {
  const id=accountId.value
  try{await ElMessageBox.confirm(message,title,{type:'warning',confirmButtonText:'确认'})}catch{return}
  if(!id || accountId.value!==id)return
  try{await action(id);ElMessage.success(success);if(accountId.value===id)await loadTab()}catch(e){ElMessage.error(e?.message||failure)}
}
function removeArticle(row){return confirmedAction(id=>deleteWechatArticle(id,row.id),`确认删除“${row.title}”？`,'删除图文草稿','草稿已删除','删除失败')}
function pushArticle(row){return confirmedAction(id=>pushWechatArticle(id,row.id),'推送会创建微信草稿，确认继续？','推送微信草稿','已推送微信草稿','推送失败')}
function publishArticle(row){return confirmedAction(id=>publishWechatArticle(id,row.id),'公开发布后读者可见，确认已核对标题、封面和正文？','发布公众号图文','图文发布请求已提交','发布失败')}
function openTask(row=null){dialogAccount=accountId.value;Object.assign(taskForm,{id:null,name:'',targetType:'',msgType:'TEXT',tagId:null,openids:[],text:'',mediaId:''},row?{id:row.id,name:row.name,targetType:row.targetType,msgType:row.msgType,tagId:row.target?.tagId??null,openids:[...(row.target?.openids||[])],text:row.content?.text||'',mediaId:row.content?.mediaId||''}:{});taskDialog.value=true;void loadChoices(dialogAccount,true)}
async function saveTask(){
  const id=dialogAccount
  if(!id||accountId.value!==id)return
  if(!taskForm.name.trim())return ElMessage.warning('请填写任务名称')
  if(!taskForm.targetType)return ElMessage.warning('请选择接收范围')
  if(taskForm.targetType==='TAG' && (!Number.isSafeInteger(taskForm.tagId) || taskForm.tagId<0))return ElMessage.warning('请选择粉丝标签')
  const openids=[...new Set(taskForm.openids.map(value=>value.trim()).filter(Boolean))]
  if(taskForm.targetType==='OPENIDS' && (openids.length<2 || openids.some(value=>!/^[-_A-Za-z0-9]{10,128}$/.test(value))))return ElMessage.warning('至少填写两个有效的 OpenID')
  if(taskForm.msgType==='TEXT'?!taskForm.text.trim():!taskForm.mediaId)return ElMessage.warning('请填写消息内容或选择素材')
  const target=taskForm.targetType==='TAG'?{tagId:taskForm.tagId}:taskForm.targetType==='OPENIDS'?{openids}:{}
  const content=taskForm.msgType==='TEXT'?{text:taskForm.text}:{mediaId:taskForm.mediaId}
  saving.value=true
  try{await saveWechatMassSend(id,taskForm.id,{name:taskForm.name,targetType:taskForm.targetType,msgType:taskForm.msgType,target,content});if(accountId.value!==id)return;taskDialog.value=false;ElMessage.success('群发任务已保存');await loadTab()}
  catch(e){ElMessage.error(e?.message||'保存失败')}finally{saving.value=false}
}
function act(row,action){return confirmedAction(id=>transitionWechatMassSend(id,row.id,action),`确认${action==='submit'?'提交审批':action==='approve'?'批准':'驳回'}“${row.name}”？`,'群发审批','状态已更新','操作失败')}
function send(row){return confirmedAction(id=>sendWechatMassSend(id,row.id),'真实群发不可撤回，确认已核对内容、范围、配额并已获得单独授权？','执行真实群发','群发请求已提交','群发失败')}
onMounted(loadAccounts)
</script>
<style scoped>
.cover-preview{display:block;max-width:100%;width:180px;max-height:112px;object-fit:contain;margin-bottom:14px;border-radius:8px;border:1px solid var(--el-border-color-light)}
.choice-empty{padding:8px 12px;color:var(--el-text-color-secondary)}
.el-form-item :deep(.el-select){width:100%}
</style>
<style scoped>.wechat-content{display:grid;gap:16px}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px}.toolbar strong,.toolbar span{display:block}.toolbar strong{font-size:14px}.toolbar span{margin-top:4px;color:var(--el-text-color-secondary);font-size:12px}.content-tabs{background:var(--el-bg-color);border:1px solid var(--el-border-color-light);border-radius:14px;padding:0 18px 18px}.panel-head{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:8px 0 14px}.panel-head strong,.panel-head span{display:block}.panel-head strong{font-size:14px}.panel-head span{margin-top:4px;color:var(--el-text-color-secondary);font-size:12px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}@media(max-width:768px){.toolbar,.panel-head{align-items:stretch;flex-direction:column}.toolbar :deep(.el-select){width:100%!important}.grid{grid-template-columns:1fr}}</style>
