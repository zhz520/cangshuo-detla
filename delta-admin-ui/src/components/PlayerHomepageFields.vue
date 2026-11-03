<template>
 <el-form-item label="头像"><div><ImageUpload v-model="model.avatar"/><el-button v-if="model.avatar" link @click="model.avatar=''">清除</el-button><div class="field-tip">最佳比例 1:1 方图，建议 640×640 像素以上；打手列表展示此头像。</div></div></el-form-item>
 <el-form-item label="主页封面"><div><ImageUpload v-model="model.coverImage"/><el-button v-if="model.coverImage" link @click="model.coverImage=''">清除</el-button><div class="field-tip">最佳比例 15:8 横图，建议 1500×800 像素以上；主页会完整展示，人物和文字请避开边缘。</div></div></el-form-item>
 <el-form-item label="一句语音"><ProfileAudioUpload v-model="model.voiceUrl" v-model:duration="model.voiceDuration" @busy="$emit('busy',$event)"/></el-form-item>
 <el-form-item label="TA的相册"><div><div class="gallery"><div v-for="(image,index) in model.albumImages" :key="index" class="album-slot"><ImageUpload v-model="model.albumImages[index]"/><el-button link type="danger" @click="model.albumImages.splice(index,1)">删除</el-button></div></div><el-button v-if="model.albumImages.length<9" @click="model.albumImages.push('')">添加相片</el-button><div class="field-tip">最多9张，最佳比例 1:1 方图，建议 1000×1000 像素以上；保存时忽略空位置。</div></div></el-form-item>
 <el-form-item label="TA的故事"><el-input v-model="model.story" type="textarea" :rows="7" maxlength="5000" show-word-limit placeholder="打手的游戏经历和故事，以纯文本展示"/></el-form-item>
</template>
<script setup>
import ImageUpload from './ImageUpload.vue'
import ProfileAudioUpload from './ProfileAudioUpload.vue'
defineProps({model:{type:Object,required:true}})
defineEmits(['busy'])
</script>
<style scoped>.gallery{display:flex;flex-wrap:wrap;gap:14px}.album-slot{display:flex;flex-direction:column;align-items:center}.field-tip{font-size:12px;line-height:1.8;color:#969aa1;margin:8px 0}</style>
