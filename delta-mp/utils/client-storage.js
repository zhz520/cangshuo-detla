// Runtime configuration survives customer updates; an empty prefix preserves existing Delta sessions.
const PREFIX = globalThis.__CANGSHUO_INSTANCE__?.storagePrefix || ''
export const clientStorage = {
  get: key => uni.getStorageSync(PREFIX + key),
  set: (key, value) => uni.setStorageSync(PREFIX + key, value),
  remove: key => uni.removeStorageSync(PREFIX + key)
}
