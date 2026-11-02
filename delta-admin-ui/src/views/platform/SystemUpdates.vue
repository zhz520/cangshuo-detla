<template>
  <div class="updates-page" v-loading="loading">
    <el-card shadow="never"><template #header><div class="heading"><h2>系统更新</h2><el-button :loading="checking" @click="check">检查正式版</el-button></div></template>
      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon/>
      <el-descriptions v-if="remote" :column="isCompact?1:2" border><el-descriptions-item label="当前版本">{{ remote.currentVersion }}</el-descriptions-item><el-descriptions-item label="最新正式版">{{ updates.latestVersion||'尚未获取' }}</el-descriptions-item><el-descriptions-item label="版本来源">授权运营中心正式版本</el-descriptions-item><el-descriptions-item label="上次检查">{{ time(updates.checkedAt) }}</el-descriptions-item><el-descriptions-item label="维护期至">{{ time(remote.project?.maintenanceUntil) }}</el-descriptions-item><el-descriptions-item label="更新状态">{{ updates.updateAvailable?'发现新版本':'暂无更高版本' }}</el-descriptions-item></el-descriptions>
      <el-alert v-if="updates.checkError" class="notice" :title="updates.checkError" type="warning" :closable="false"/>
      <el-alert v-if="remote && !remote.connected" class="notice" title="尚未连接授权中心，请检查授权和连接配置。" type="warning" :closable="false"/>
      <el-alert v-if="job" class="notice" :title="jobMessage" :type="job.status==='SUCCEEDED'?'success':['FAILED','ROLLED_BACK'].includes(job.status)?'error':'info'" :closable="false"/>
    </el-card>
    <el-card v-if="updates.updateAvailable && !choosing" shadow="never"><p>发现 {{ updates.releases?.length||0 }} 个较新正式版本。点击更新，选择各版本中需要的内容。</p><el-button type="primary" @click="choose">更新系统</el-button></el-card>
    <template v-if="choosing">
      <el-card v-for="release in updates.releases" :key="release.version" shadow="never"><template #header><div class="heading"><strong>正式版 {{ release.version }}</strong><a v-if="publicReleaseNotesUrl(release.sourceUrl)" :href="publicReleaseNotesUrl(release.sourceUrl)" target="_blank" rel="noopener noreferrer">查看发布说明</a></div></template><p class="release-notes">{{ release.notes }}</p>
        <el-alert v-if="!release.items?.length" title="此版本尚未提供可选更新包，发布方配置更新内容后即可选择。" type="info" :closable="false"/>
        <el-checkbox v-for="item in release.items" :key="item.id" :model-value="selected.includes(release.version+':'+item.id)" :disabled="item.required || isInstalled(release.version+':'+item.id)" @change="value=>toggle(release,item,value)"><strong>{{ item.name }}</strong><el-tag v-if="item.required" size="small" type="warning">必要更新</el-tag><el-tag v-if="isInstalled(release.version+':'+item.id)" size="small" type="success">已安装</el-tag><span class="component-tip">{{ item.description }}</span></el-checkbox>
      </el-card>
      <el-card shadow="never"><p>可选功能可以逐项取消；必要安全修复、关联依赖和付费模块要求会由授权中心复核。安装前备份业务数据库、上传文件和实例配置，健康检查失败时回退程序。</p><el-alert v-if="!remote?.installerAvailable" title="此实例尚未接入更新执行器，请联系维护人员。" type="warning" :closable="false"/><el-button class="notice" type="primary" :disabled="!remote?.installerAvailable || !selected.length || running" :loading="installing" @click="install">安装所选内容（{{ selected.length }} 项）</el-button></el-card>
    </template>
  </div>
</template>
<script setup>
import {computed,onMounted,onUnmounted,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {useResponsive} from '@/utils/responsive'
import {getRemoteUpdates,checkRemoteUpdates,installRemoteUpdates} from '@/api/platform'
import {defaultUpdateSelection,toggleUpdateSelection,publicReleaseNotesUrl} from '@/utils/update-selection'
const {isCompact}=useResponsive(),remote=ref(null),loading=ref(false),checking=ref(false),installing=ref(false),error=ref(''),choosing=ref(false),selected=ref([])
const updates=computed(()=>remote.value?.updates||{}),job=computed(()=>remote.value?.job),running=computed(()=>job.value&&['QUEUED','DOWNLOADING','BACKING_UP','INSTALLING'].includes(job.value.status)),jobMessage=computed(()=>({QUEUED:'更新已排队',DOWNLOADING:'正在下载并校验更新包',BACKING_UP:'正在备份业务数据与文件',INSTALLING:'正在安装并检查服务健康',SUCCEEDED:'更新成功',ROLLED_BACK:'更新失败，程序已回退',FAILED:job.value?.message||'更新失败，请联系维护人员'}[job.value?.status]||job.value?.status))
let timer
const time=value=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—'
const isInstalled=key=>remote.value?.installedItems?.includes(key) || (job.value?.status==='SUCCEEDED'&&job.value?.items?.includes(key))
async function load(){try{remote.value=(await getRemoteUpdates()).data}catch{error.value='版本信息暂时不可用，请稍后重试。'}}
async function check(){checking.value=true;error.value='';try{remote.value=(await checkRemoteUpdates()).data;ElMessage.success('版本检查完成')}catch{error.value='版本检查失败，保留上次成功获取的信息。'}finally{checking.value=false}}
function choose(){choosing.value=true;selected.value=defaultUpdateSelection(updates.value.releases,remote.value?.installedItems||[])}
function toggle(release,item,value){selected.value=toggleUpdateSelection(updates.value.releases,selected.value,release,item,value)}
async function install(){try{await ElMessageBox.confirm('确认安装所选更新？安装期间业务会短暂停止，完成健康检查后恢复。','安装系统更新',{type:'warning'});installing.value=true;const response=await installRemoteUpdates(selected.value);remote.value={...remote.value,job:response.data};ElMessage.success('更新任务已提交')}catch(error){if(error?.message && !['cancel','close'].includes(error.message))ElMessage.error(error.message)}finally{installing.value=false}}
onMounted(async()=>{loading.value=true;await load();if(!remote.value?.connected)await check();loading.value=false;timer=setInterval(()=>{if(running.value)load()},5000)})
onUnmounted(()=>clearInterval(timer))
</script>
<style scoped>.updates-page{display:grid;gap:18px}.heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}h2{margin:0;font-size:20px}p{color:#555b63;line-height:1.9;font-size:14px}.notice{margin-top:18px}.component-tip{color:#7b879a;margin-left:10px;font-size:12px}.release-notes{white-space:pre-wrap}:deep(.el-checkbox){display:flex;height:auto;margin:14px 0;white-space:normal}:deep(.el-checkbox__label){white-space:normal}:deep(.el-tag){margin-left:8px}</style>
