<template>
  <view class="profile-page">
    <!-- 头像区域 -->
    <view class="avatar-section">
      <!-- #ifdef MP-WEIXIN -->
      <button :disabled="uploading || saving" class="avatar-btn" open-type="chooseAvatar" @chooseavatar="onChooseWxAvatar">
        <AppImage :width="160" class="avatar-img" :src="avatar || '/static/images/delta-default-avatar.png'" mode="aspectFill" />
        <view class="avatar-edit-hint">
          <text class="hint-text">{{ uploading ? '正在上传…' : '点击更换头像' }}</text>
        </view>
      </button>
      <!-- #endif -->
      <!-- #ifndef MP-WEIXIN -->
      <button :disabled="uploading || saving" class="avatar-btn" @click="chooseAvatarImage">
        <AppImage :width="160" class="avatar-img" :src="avatar || '/static/images/delta-default-avatar.png'" mode="aspectFill" />
        <view class="avatar-edit-hint">
          <text class="hint-text">{{ uploading ? '正在上传…' : '点击更换头像' }}</text>
        </view>
      </button>
      <!-- #endif -->
      <text v-if="uploadError" role="alert" class="avatar-ratio-tip">{{ uploadError }}</text>
      <text class="avatar-ratio-tip">建议上传 1:1 正方形头像，人物居中，最小 300×300；未上传时显示 DELTA 默认头像。</text>
    </view>

    <!-- 表单 -->
    <view class="form-card">
      <view class="form-item">
        <text class="label">昵称</text>
        <input v-model="nickname" placeholder="请输入昵称" class="input" />
      </view>
      <view class="form-item" v-if="phone">
        <text class="label">手机号</text>
        <text class="value">{{ phone }}</text>
      </view>
      <view class="form-item" v-if="userId">
        <text class="label">用户ID</text>
        <text class="value uid">{{ userId }}</text>
      </view>
    </view>

    <button :disabled="uploading || saving" class="btn-save" @click="save">保存</button>
  </view>
</template>

<script setup>
import { useAvatarUpload } from '@/composables/useAvatarUpload'
import AppImage from '@/components/AppImage.vue'
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { getUserProfile, updateUserProfile } from '@/api/user'
import { chooseAndUpload, upload } from '@/api/file'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const nickname = ref('')
const avatar = ref('')
const saving = ref(false)
const { uploading, uploadError, changeAvatar } = useAvatarUpload(avatar, saving)
const phone = ref('')
const userId = ref('')

onLoad(async () => {
  try {
    const res = await getUserProfile()
    const d = res.data || {}
    nickname.value = d.nickname || ''
    avatar.value = d.avatar || ''
    phone.value = d.phone || ''
    userId.value = d.id || ''
  } catch (e) { /* ignore */ }
})

async function onChooseWxAvatar(e) {
  const tempUrl = e.detail.avatarUrl
  if (!tempUrl) return
  await changeAvatar(() => upload(tempUrl, undefined, { silent: true }))
}

function chooseAvatarImage() {
  return changeAvatar(async () => (await chooseAndUpload(1, { silent: true }))[0])
}

async function save() {
  if (uploading.value || saving.value) return
  if (!nickname.value.trim()) {
    return uni.showToast({ title: '请输入昵称', icon: 'none' })
  }
  saving.value = true
  try {
    await updateUserProfile({ nickname: nickname.value, avatar: avatar.value })
    await userStore.fetchProfile(true)
    uni.showToast({ title: '已保存' })
    setTimeout(() => uni.navigateBack({ complete: () => { saving.value = false } }), 1500)
  } catch (e) {
    saving.value = false
    uni.showToast({ title: '保存失败', icon: 'none' })
  }
}
</script>

<style lang="scss" scoped>
.profile-page {
  padding: 24rpx;
  background: var(--store-soft);
  min-height: var(--app-page-height, 100vh);
}

.avatar-section {
  display: flex;
  justify-content: center;
  padding: 48rpx 0 32rpx;
}

.avatar-btn {
  background: transparent;
  border: none;
  padding: 0;
  margin: 0;
  line-height: 1;
  display: flex;
  flex-direction: column;
  align-items: center;

  &::after { border: none; }
}

.avatar-img {
  width: 160rpx;
  height: 160rpx;
  border-radius: 50%;
  border: 4rpx solid var(--store-line);
}

.avatar-edit-hint {
  margin-top: 16rpx;
  .hint-text {
    font-size: 24rpx;
    color: var(--store-text);
  }
}
.avatar-ratio-tip { display: block; max-width: 560rpx; margin: 14rpx auto 0; color: var(--store-muted); font-size: 21rpx; line-height: 1.5; text-align: center; }

.form-card {
  background: var(--store-surface);
  border-radius: 16rpx;
  box-shadow: 0 4rpx 20rpx rgba(23, 25, 28, 0.04);
  overflow: hidden;
}

.form-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 32rpx 28rpx;
  border-bottom: 1rpx solid var(--store-line);

  &:last-child { border-bottom: none; }

  .label {
    font-size: 28rpx;
    color: var(--store-regular);
    flex-shrink: 0;
    margin-right: 20rpx;
  }

  .input {
    flex: 1;
    min-width: 0;
    padding: 0;
    border: 0;
    background: transparent;
    text-align: right;
    font-size: 28rpx;
    color: var(--store-text);
  }

  .value {
    font-size: 28rpx;
    color: var(--store-regular);
  }

  .uid {
    font-size: 24rpx;
    color: var(--store-regular);
  }
}

.btn-save {
  height: 88rpx;
  line-height: 88rpx;
  text-align: center;
  background: var(--store-primary);
  color: #ffffff;
  font-weight: bold;
  font-size: 32rpx;
  border-radius: 999rpx;
  margin-top: 60rpx;
}
</style>
