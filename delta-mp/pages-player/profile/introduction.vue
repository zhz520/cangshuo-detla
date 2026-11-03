<template>
 <scroll-view scroll-y class="homepage-editor">
  <PageState :loading="loading" :error="loadError" @retry="load" />
  <template v-if="!loading && !loadError">
   <PageState :loading="revisionsLoading && !revisions.length" :error="revisionsError" @retry="loadRevisions" />
   <view v-if="revisions.length" class="editor-card"><text class="field-label first">最近审核</text><view v-for="row in revisions.slice(0,3)" :key="row.id"><text>{{reviewStatuses[row.status]||row.status}}</text><text class="tip">{{row.review_note||row.risk_reason}}</text></view></view>
   <view class="editor-card"><view class="editor-heading"><text>我的打手主页</text><text class="level-name">{{ levelName }}</text></view><text class="tip">等级分类由管理员设置，下面的资料通过系统检测和人工审核后才会展示给用户。</text><text class="field-label">头像</text><ImageUploader v-model="avatarImages" :max="1"/><text class="tip">使用 1:1 方图，建议 640×640 像素以上；打手列表将展示这张头像。</text><text class="field-label">昵称</text><input v-model="form.nickname" maxlength="32" placeholder="请输入昵称"/><text class="field-label">主页封面</text><ImageUploader v-model="coverImages" :max="1"/><text class="tip">使用 15:8 横图，建议 1500×800 像素以上；主页会完整展示图片，请勿把人物或文字放在最边缘。</text></view>
   <view class="editor-card"><text class="field-label first">个人简介</text><textarea v-model="form.introduction" maxlength="1000" placeholder="介绍你擅长的游戏和服务特点"/><text class="counter">{{ form.introduction.length }}/1000</text></view>
   <view class="editor-card"><text class="field-label first">一句语音</text><ProfileVoiceUploader v-model="form.voiceUrl" v-model:duration="form.voiceDuration" @busy="voiceBusy=$event"/></view>
   <view class="editor-card"><text class="field-label first">TA的相册</text><text class="tip">最多9张，使用 1:1 方图，建议 1000×1000 像素以上；按上传顺序展示，可点击图片预览。</text><ImageUploader v-model="form.albumImages" :max="9"/></view>
   <view class="editor-card"><text class="field-label first">TA的故事</text><textarea v-model="form.story" maxlength="5000" class="story-input" placeholder="分享你的游戏经历、接单故事和个人风格"/><text class="counter">{{ form.story.length }}/5000</text></view>
   <text v-if="submitError" class="submit-error" role="alert">{{ submitError }}</text>
   <text v-if="alreadySubmitted" class="submitted-tip">已提交审核，修改资料后可再次提交</text>
   <view class="editor-buttons"><button :disabled="saving||loading||voiceBusy||alreadySubmitted" :loading="saving" @click="save(false)">{{ saving ? '提交中…' : alreadySubmitted ? '已提交审核' : '提交审核' }}</button><button class="preview" :disabled="saving||loading||voiceBusy||alreadySubmitted" @click="save(true)">提交并查看主页</button></view>
  </template><view class="editor-bottom"/>
 </scroll-view>
</template>
<script setup>
import {ref,reactive,computed} from 'vue'
import {onLoad,onUnload} from '@dcloudio/uni-app'
import {getPlayerProfile,updatePlayerHomepage,getHomepageRevisions} from '@/api/player'
import ImageUploader from '@/components/ImageUploader.vue'
import ProfileVoiceUploader from '@/components/ProfileVoiceUploader.vue'
import {usePlayerStore} from '@/store/player'
import PageState from '@/components/PageState.vue'
const revisions=ref([]),reviewStatuses={PENDING_SYSTEM:'待系统检测',PENDING_REVIEW:'待人工审核',APPROVED:'已通过',REJECTED:'已驳回'}
const form=reactive({nickname:'',introduction:'',albumImages:[],story:'',voiceUrl:'',voiceDuration:0})
const avatarImages=ref([]),coverImages=ref([]),levelName=ref(''),loading=ref(true),loadError=ref(''),saving=ref(false),voiceBusy=ref(false)
const revisionsLoading=ref(true),revisionsError=ref(''),submitError=ref(''),submittedSignature=ref('')
let playerId,status,disposed=false,loadTask,revisionTask,loadVersion=0,revisionVersion=0
const alreadySubmitted=computed(()=>!!submittedSignature.value&&submittedSignature.value===JSON.stringify(payload()))
onLoad(()=>{load();loadRevisions()})
onUnload(()=>{disposed=true;loadVersion++;revisionVersion++})
function load(){
 if(disposed)return Promise.resolve()
 if(loadTask)return loadTask
 const current=++loadVersion
 loading.value=true;loadError.value=''
 loadTask=(async()=>{
  try{
   const {data}=await getPlayerProfile({loading:false,silent:true})
   if(!data||data.id==null)throw new Error('主页资料异常，请重试')
   if(disposed||current!==loadVersion)return
   playerId=data.id;status=data.status;levelName.value=data.levelName||`Lv.${data.level||1}`
   for(const key of ['nickname','introduction','story','voiceUrl'])form[key]=String(data[key]??'')
   form.albumImages=Array.isArray(data.albumImages)?[...data.albumImages]:[]
   form.voiceDuration=Number(data.voiceDuration)||0
   avatarImages.value=data.avatar?[data.avatar]:[];coverImages.value=data.coverImage?[data.coverImage]:[]
  }catch(e){if(!disposed&&current===loadVersion)loadError.value=e?.msg||e?.message||'主页加载失败，请重试'}
  finally{if(!disposed&&current===loadVersion){loading.value=false;loadTask=null}}
 })()
 return loadTask
}
function loadRevisions(){
 if(disposed)return Promise.resolve()
 if(revisionTask)return revisionTask
 const current=++revisionVersion
 revisionsLoading.value=true;revisionsError.value=''
 revisionTask=(async()=>{
  try{
   const {data}=await getHomepageRevisions({loading:false,silent:true})
   if(!Array.isArray(data))throw new Error('审核记录数据异常，请重试')
   if(!disposed&&current===revisionVersion)revisions.value=data
  }catch(e){if(!disposed&&current===revisionVersion)revisionsError.value=e?.msg||e?.message||'审核记录加载失败，请重试'}
  finally{if(!disposed&&current===revisionVersion){revisionsLoading.value=false;revisionTask=null}}
 })()
 return revisionTask
}
function payload(){return {...form,albumImages:[...form.albumImages],nickname:form.nickname.trim(),avatar:avatarImages.value[0]||'',coverImage:coverImages.value[0]||''}}
async function save(preview){
 if(disposed||saving.value||loading.value||loadError.value||voiceBusy.value||alreadySubmitted.value)return
 const data=payload()
 if(!data.nickname)return uni.showToast({title:'请输入昵称',icon:'none'})
 saving.value=true;submitError.value=''
 try{
  await updatePlayerHomepage(data)
  if(disposed)return
  submittedSignature.value=JSON.stringify(data)
  // A successful submission stays successful even if the follow-up reads fail.
  uni.showToast({title:preview&&status!=='ACTIVE'?'已保存，审核通过后主页可见':'已提交审核，当前主页保持原资料',icon:'none'})
  try{await usePlayerStore().fetchProfile({loading:false,silent:true})}catch(_){}
  if(disposed)return
  // Wait out an older history read before fetching the newly submitted revision.
  if(revisionTask)await revisionTask
  if(disposed)return
  await loadRevisions()
  if(!disposed&&preview&&status==='ACTIVE')uni.navigateTo({url:`/pages/players/detail?id=${encodeURIComponent(playerId)}`})
 }catch(e){if(!disposed)submitError.value=e?.msg||e?.message||'提交失败，请重试'}
 finally{saving.value=false}
}
</script>
<style lang="scss" scoped>
.homepage-editor{height:var(--app-page-height, 100vh);background:#f7f8f9;box-sizing:border-box}.editor-card{margin:24rpx;padding:28rpx;background:#fff;border:1rpx solid #eeeff1;border-radius:20rpx}.editor-heading{display:flex;align-items:center;justify-content:space-between;gap:16rpx;font-size:32rpx;font-weight:700}.level-name{background:#17191c;color:#fff;font-size:22rpx;border-radius:999rpx;padding:8rpx 20rpx}.tip{display:block;color:#858992;font-size:23rpx;line-height:1.7;margin:16rpx 0 22rpx}.field-label{display:block;font-size:28rpx;font-weight:600;margin:28rpx 0 18rpx;&.first{margin-top:0}}input,textarea{width:100%;box-sizing:border-box;background:#f7f8f9;border:1rpx solid #eeeff1;border-radius:12rpx;font-size:27rpx;padding:20rpx;line-height:1.7}input{height:80rpx}textarea{height:240rpx}.story-input{height:400rpx}.counter{display:block;text-align:right;color:#969aa1;font-size:22rpx;margin-top:12rpx}.editor-buttons{display:flex;gap:16rpx;padding:0 24rpx;button{flex:1;border-radius:999rpx;font-size:27rpx;background:#17191c;color:#fff;&.preview{color:#17191c;background:#fff;border:1rpx solid #e6e7e9}}}.retry{text-align:center;color:#858992}.editor-bottom{height:calc(50rpx + var(--app-safe-bottom, env(safe-area-inset-bottom)))}
.submit-error,.submitted-tip{display:block;margin:16rpx 24rpx;font-size:25rpx;line-height:1.7;color:#b84b55}.submitted-tip{color:#73777e}.editor-buttons button[disabled]{opacity:.5}
</style>
