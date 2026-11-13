import assert from 'node:assert/strict'
import test from 'node:test'

import { thumbnailUrl } from '../utils/image.js'

test('H5 upload images support both legacy and tenant-scoped thumbnail routes', () => {
  const legacy = '/file/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg'
  const tenant = '/file/742/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg'

  assert.equal(thumbnailUrl(legacy, 640), `/file/thumbnail/640/${legacy.slice(6)}`)
  assert.equal(thumbnailUrl(tenant, 640), '/file/thumbnail/640/742/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg')
  assert.equal(thumbnailUrl(tenant, 320), '/file/thumbnail/320/742/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg')
})

test('H5 leaves external, audio and already-derived image URLs unchanged', () => {
  assert.equal(thumbnailUrl('https://example.com/a.jpg', 640), 'https://example.com/a.jpg')
  assert.equal(thumbnailUrl('/file/2026/11/08/voice.mp3', 640), '/file/2026/11/08/voice.mp3')
  assert.equal(
    thumbnailUrl('/file/thumbnail/640/742/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg', 640),
    '/file/thumbnail/640/742/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg'
  )
})
