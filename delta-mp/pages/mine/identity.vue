<template>
  <view class="identity-page">
    <view class="status-card" :class="statusClass">
      <text class="status-title">{{ statusTitle }}</text>
      <text class="status-desc">{{ statusDescription }}</text>
      <text v-if="status.reviewNote" class="review-note">审核说明：{{ status.reviewNote }}</text>
    </view>

    <view v-if="!status.available" class="empty-card">
      <text>实名认证功能尚未开通，请联系平台管理员。</text>
    </view>

    <template v-else-if="canSubmit">
      <view class="form-card">
        <view class="field">
          <text class="label">真实姓名</text>
          <input v-model="form.realName" maxlength="32" placeholder="请输入证件上的姓名" />
        </view>
        <view class="field">
          <text class="label">身份证号码</text>
          <input v-model="form.idNumber" maxlength="18" placeholder="请输入18位身份证号码" />
        </view>
        <view v-if="!status.autoAvailable" class="upload-section">
          <text class="label">身份证照片</text>
          <text class="upload-tip">照片仅用于人工审核，审核完成后自动清除。</text>
          <view class="upload-grid">
            <view v-for="item in uploadItems" :key="item.side" class="upload-card" @click="choose(item.side)">
              <image v-if="item.preview" :src="item.preview" mode="aspectFill" />
              <view v-else class="upload-placeholder"><text>＋</text><text>{{ uploading === item.side ? '上传中...' : item.label }}</text></view>
              <text v-if="item.uploaded" class="uploaded">已上传，点击更换</text>
            </view>
          </view>
        </view>
        <view class="agreement" @click="form.privacyAccepted=!form.privacyAccepted">
          <text class="checkbox">{{ form.privacyAccepted ? '☑' : '☐' }}</text>
          <text v-if="status.autoAvailable">我同意平台仅为实名认证目的处理姓名和证件号码；本次核验费用由平台承担，不向用户单独收费。</text>
          <text v-else>我同意平台仅为实名认证目的处理姓名、证件号码和证件照片，并知悉审核完成后证件照片会被清除。</text>
        </view>
      </view>
      <button class="submit-btn" :loading="submitting" @click="submit">{{ status.autoAvailable ? '核验并完成实名' : '提交实名认证' }}</button>
    </template>

    <view v-else-if="status.status==='PENDING'" class="info-card">
      <text>资料已提交，请等待管理员审核。</text>
      <text v-if="status.submittedAt" class="submitted">提交时间：{{ status.submittedAt }}</text>
    </view>
    <view v-else-if="status.status==='APPROVED'" class="info-card success">
      <text>实名认证已通过，认证姓名：{{ status.realName }}</text>
      <text>证件号码：{{ status.idNumberMasked }}</text>
    </view>
  </view>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { autoVerifyIdentity, getIdentityStatus, submitIdentityVerification, uploadIdentityImage } from '@/api/user'

const status=ref({available:false,status:'UNAVAILABLE',reason:'',realName:'',idNumberMasked:'',reviewNote:'',submittedAt:'',reviewedAt:'',hasFrontImage:false,hasBackImage:false,autoAvailable:false,autoReason:''})
const form=reactive({realName:'',idNumber:'',privacyAccepted:false})
const uploading=ref(''),submitting=ref(false),previews=reactive({front:'',back:''}),uploaded=reactive({front:false,back:false})
const uploadItems=computed(()=>[{side:'front',label:'身份证人像面',preview:previews.front,uploaded:uploaded.front},{side:'back',label:'身份证国徽面',preview:previews.back,uploaded:uploaded.back}])
const canSubmit=computed(()=>status.value.available&&['NOT_SUBMITTED','DRAFT','REJECTED'].includes(status.value.status))
const statusTitle=computed(()=>({APPROVED:'已认证',PENDING:'审核中',REJECTED:'审核未通过',DRAFT:'资料填写中',NOT_SUBMITTED:'未认证',UNAVAILABLE:'功能未开通'}[status.value.status]||'未认证'))
const statusDescription=computed(()=>({APPROVED:'实名资料已通过审核。',PENDING:'资料已提交，审核期间不能修改。',REJECTED:'请根据审核说明修改后重新提交。',DRAFT:status.value.autoAvailable?'填写姓名和证件号码后完成核验。':'请完成资料和证件照片后提交。',NOT_SUBMITTED:status.value.autoAvailable?'填写姓名和证件号码，系统将进行权威二要素核验。':'提交实名资料后由管理员人工审核。',UNAVAILABLE:status.value.reason||'实名认证功能尚未开通。'}[status.value.status]||''))
const statusClass=computed(()=>({approved:status.value.status==='APPROVED',pending:status.value.status==='PENDING',rejected:status.value.status==='REJECTED'}))

async function load(){
  const response=await getIdentityStatus({loading:false})
  status.value=Object.assign({available:false,status:'UNAVAILABLE',reason:'',realName:'',idNumberMasked:'',reviewNote:'',submittedAt:'',reviewedAt:'',hasFrontImage:false,hasBackImage:false,autoAvailable:false,autoReason:''},response.data||{})
  form.realName=status.value.realName||form.realName
  uploaded.front=!!status.value.hasFrontImage
  uploaded.back=!!status.value.hasBackImage
}

function choose(side){
  if(uploading.value||!canSubmit.value)return
  uni.chooseImage({count:1,sizeType:['compressed'],sourceType:['album','camera'],success:async result=>{
    const path=(result.tempFilePaths||[])[0]
    if(!path)return
    previews[side]=path;uploading.value=side
    try{await uploadIdentityImage(path,side);uploaded[side]=true;uni.showToast({title:'照片已上传',icon:'success'})}
    catch(_){previews[side]='';uploaded[side]=false}
    finally{uploading.value=''}
  }})
}

function validId(value){
  const id=String(value||'').trim().toUpperCase()
  if(!/^[1-9][0-9]{16}[0-9X]$/.test(id))return false
  const weights=[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2],codes='10X98765432'
  return id[17]===codes[[...id.slice(0,17)].reduce((sum,char,index)=>sum+Number(char)*weights[index],0)%11]
}

async function submit(){
  if(submitting.value)return
  if(!form.realName.trim())return uni.showToast({title:'请输入真实姓名',icon:'none'})
  if(!validId(form.idNumber))return uni.showToast({title:'请输入有效的身份证号码',icon:'none'})
  if(!status.value.autoAvailable&&(!uploaded.front||!uploaded.back))return uni.showToast({title:'请上传身份证正反面照片',icon:'none'})
  if(!form.privacyAccepted)return uni.showToast({title:'请先同意实名认证隐私说明',icon:'none'})
  submitting.value=true
  try{
    const payload={realName:form.realName.trim(),idNumber:form.idNumber.trim().toUpperCase(),privacyAccepted:true}
    if(status.value.autoAvailable)await autoVerifyIdentity(payload)
    else await submitIdentityVerification(payload)
    await load();form.idNumber='';uni.showToast({title:status.value.autoAvailable?'实名认证已通过':'已提交审核',icon:'success'})
  }
  finally{submitting.value=false}
}

onShow(()=>{load().catch(()=>{uni.showToast({title:'实名认证信息加载失败',icon:'none'})})})
</script>

<style lang="scss" scoped>
.identity-page{min-height:var(--app-page-height,100vh);padding:24rpx;background:#f4f5f6}.status-card{display:flex;flex-direction:column;gap:12rpx;padding:32rpx;border-radius:20rpx;background:#17191c;color:#fff;margin-bottom:24rpx}.status-card.approved{background:#16794b}.status-card.rejected{background:#a3323c}.status-card.pending{background:#8b6618}.status-title{font-size:38rpx;font-weight:800}.status-desc,.review-note{font-size:26rpx;line-height:1.7;color:rgba(255,255,255,.82)}.review-note{padding-top:10rpx;border-top:1rpx solid rgba(255,255,255,.2)}.form-card,.empty-card,.info-card{padding:28rpx;border-radius:20rpx;background:#fff;margin-bottom:24rpx}.field{display:flex;flex-direction:column;gap:14rpx;padding:22rpx 0;border-bottom:1rpx solid #eef0f2}.label{font-size:28rpx;font-weight:700;color:#17191c}.field input{height:76rpx;padding:0 20rpx;border:1rpx solid #d9dde3;border-radius:12rpx;font-size:28rpx}.upload-section{padding:24rpx 0}.upload-tip{display:block;margin-top:8rpx;color:#7b879a;font-size:24rpx;line-height:1.6}.upload-grid{display:grid;grid-template-columns:1fr 1fr;gap:18rpx;margin-top:18rpx}.upload-card{position:relative;height:220rpx;border:1rpx dashed #c9ced6;border-radius:16rpx;overflow:hidden;background:#fafbfc}.upload-card image{width:100%;height:100%}.upload-placeholder{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10rpx;color:#73777e;font-size:26rpx}.upload-placeholder text:first-child{font-size:46rpx;color:#17191c}.uploaded{position:absolute;left:0;right:0;bottom:0;padding:8rpx 10rpx;background:rgba(23,25,28,.72);color:#fff;font-size:22rpx;text-align:center}.agreement{display:flex;align-items:flex-start;gap:10rpx;margin-top:24rpx;color:#555b63;font-size:24rpx;line-height:1.65}.checkbox{font-size:32rpx;color:#17191c;line-height:1.2}.submit-btn{margin:0;height:92rpx;line-height:92rpx;border-radius:16rpx;background:#17191c;color:#fff;font-size:30rpx}.info-card{display:flex;flex-direction:column;gap:12rpx;color:#555b63;line-height:1.7;font-size:27rpx}.info-card.success{color:#16794b}.submitted{color:#8b919b;font-size:24rpx}@media(max-width:420px){.upload-grid{grid-template-columns:1fr}.upload-card{height:240rpx}}
</style>
