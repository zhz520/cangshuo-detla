import assert from 'node:assert/strict'
import test from 'node:test'

import { imageRecoveryUrl, thumbnailUrl } from '../utils/image.js'

test('H5 upload images support both legacy and tenant-scoped thumbnail routes', () => {
  const legacy = '/file/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg'
  const tenant = '/file/742/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg'

  assert.equal(thumbnailUrl(legacy, 640), `/file/thumbnail/640/${legacy.slice(6)}`)
  assert.equal(thumbnailUrl(tenant, 640), '/file/thumbnail/640/742/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg')
  assert.equal(thumbnailUrl(tenant, 320), '/file/thumbnail/320/742/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg')
})

test('image error recovery only bypasses cache for unsigned same-origin assets', () => {
  const origin = 'https://shop.example.com'
  assert.equal(imageRecoveryUrl('/static/images/banner.jpg', origin, 42), '/static/images/banner.jpg?_imageRetry=42')
  assert.equal(imageRecoveryUrl(origin + '/file/2026/11/01/a.jpg', origin, 42), '/file/2026/11/01/a.jpg?_imageRetry=42')
  for (const url of ['/file/a.jpg?sig=abcd', '//other.example.com/file/a.jpg', 'data:image/png;base64,abc',
    '/api/banner.png', '/file/a.jpg?_imageRetry=41', 'https://fake@shop.example.com/static/abc.png']) {
    assert.equal(imageRecoveryUrl(url, origin, 42), '', url)
  }
})

test('H5 leaves external, audio and already-derived image URLs unchanged', () => {
  assert.equal(thumbnailUrl('https://example.com/a.jpg', 640), 'https://example.com/a.jpg')
  assert.equal(thumbnailUrl('/file/2026/11/08/voice.mp3', 640), '/file/2026/11/08/voice.mp3')
  assert.equal(
    thumbnailUrl('/file/thumbnail/640/742/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg', 640),
    '/file/thumbnail/640/742/2026/11/08/fbd12e1d73ee4d1097453673aebdc561.jpg'
  )
})
