import request from '@/utils/request'

export function uploadFile(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request({ url: '/common/file/upload', method: 'post', data: formData, headers: { 'Content-Type': 'multipart/form-data' } })
}

export function uploadProfileAudio(file) {
  const data = new FormData()
  data.append('file', file)
  return request({ url: '/common/file/profile-audio', method: 'post', data, headers: { 'Content-Type': 'multipart/form-data' } })
}
