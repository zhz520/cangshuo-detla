import { ref } from 'vue'

export function useAvatarUpload(avatar, saving) {
  const uploading = ref(false)
  const uploadError = ref('')
  async function changeAvatar(readUrl) {
    if (uploading.value || saving.value) return
    uploading.value = true
    uploadError.value = ''
    try {
      const url = await readUrl()
      if (url) avatar.value = url
    } catch (err) {
      if (!/cancel/i.test(err?.errMsg || err?.message || '') && err?.code !== 401) uploadError.value = '头像上传失败，请重新选择'
    } finally {
      uploading.value = false
    }
  }
  return { uploading, uploadError, changeAvatar }
}
