export function thumbnailUrl(source, width = 640) {
  if (typeof source !== 'string') return ''
  // Only local public uploads use our thumbnail service. External hosts are unchanged.
  if (!/^\/file\/(?:[a-z0-9][a-z0-9-]{0,47}\/)?\d{4}\/\d{2}\/\d{2}\/[a-f\d-]{32,36}\.(png|jpe?g|gif)$/i.test(source)) return source
  const size = [160, 320, 640, 1080].find(value => value >= width) || 1080
  return source.replace('/file/', `/file/thumbnail/${size}/`)
}

// A failed conditional request can leave the browser without a usable cached
// image. Retry only our unsigned public assets and only after an image error.
export function imageRecoveryUrl(source, origin, attempt) {
  if (!source || !origin) return ''
  try {
    const url = new URL(source, origin)
    if (url.origin !== origin || url.username || url.password || url.search ||
        !/^\/(?:file|static)\//.test(url.pathname)) return ''
    url.searchParams.set('_imageRetry', String(attempt))
    return url.pathname + url.search + url.hash
  } catch (_) { return '' }
}
