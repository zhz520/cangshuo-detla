import test from 'node:test'
import assert from 'node:assert/strict'
import { thumbnailUrl } from '../src/utils/image.js'

const source = '/file/2026/11/03/0123456789abcdef0123456789abcdef.jpg'

test('admin image lists use the nearest cached thumbnail size', () => {
  assert.equal(thumbnailUrl(source, 50), `/file/thumbnail/160/${source.slice(6)}`)
  assert.equal(thumbnailUrl(source, 320), `/file/thumbnail/320/${source.slice(6)}`)
  assert.equal(thumbnailUrl(source, 900), `/file/thumbnail/1080/${source.slice(6)}`)
})

test('tenant-scoped uploads use the tenant thumbnail route', () => {
  const tenantSource = '/file/742/2026/11/03/0123456789abcdef0123456789abcdef.jpg'
  assert.equal(thumbnailUrl(tenantSource, 320), '/file/thumbnail/320/742/2026/11/03/0123456789abcdef0123456789abcdef.jpg')
})

test('external, non-image and already-derived URLs stay untouched', () => {
  assert.equal(thumbnailUrl('https://example.com/a.jpg', 160), 'https://example.com/a.jpg')
  assert.equal(thumbnailUrl('/file/voice.mp3', 160), '/file/voice.mp3')
  assert.equal(thumbnailUrl('/file/thumbnail/160/2026/11/03/0123456789abcdef0123456789abcdef.jpg', 160), '/file/thumbnail/160/2026/11/03/0123456789abcdef0123456789abcdef.jpg')
})
