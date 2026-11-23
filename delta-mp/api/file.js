import { uploadFile } from './request'

/** 上传文件，返回URL */
export const upload = (filePath, endpoint, options) => uploadFile(filePath, endpoint, options)

/**
 * 选择并上传图片（封装uni.chooseImage + upload）
 * @param {number} count 最多选几张
 * @returns {Promise<string[]>} 上传后的URL数组
 */
export function chooseAndUpload(count = 1, options) {
  return new Promise((resolve, reject) => {
    uni.chooseImage({
      count,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: async (res) => {
        try {
          const urls = []
          for (const path of res.tempFilePaths) {
            const url = await uploadFile(path, undefined, options)
            urls.push(url)
          }
          resolve(urls)
        } catch (err) {
          reject(err)
        }
      },
      fail: reject
    })
  })
}
