const sizes = [160, 320, 640, 1080]

/** Use the backend derivative only for immutable local uploads. */
export function thumbnailUrl(source, width = 320) {
  if (typeof source !== 'string') return ''
  if (!/^\/file\/(?:[a-z0-9][a-z0-9-]{0,47}\/)?\d{4}\/\d{2}\/\d{2}\/[a-f\d-]{32,36}\.(png|jpe?g|gif)$/i.test(source)) return source
  const size = sizes.find(value => value >= Number(width || 320)) || 1080
  return source.replace('/file/', `/file/thumbnail/${size}/`)
}
