import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { register } from 'node:module'

const asModule = source => 'data:text/javascript,' + encodeURIComponent(source)
const authMock = asModule('export function getToken() { return "cs-current-token" }')

register(asModule(`
  export function resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier === '@/utils/auth' ? ${JSON.stringify(authMock)} : specifier,
      context
    )
  }
`), import.meta.url)

const api = await import('../src/api/complaint-attachment.js')

test('complaint attachment groups normalize tokens and old images without exposing URLs', () => {
  const groups = api.complaintAttachmentGroups({
    complaint: {
      attachments: [{ token: 'evidence-a', kind: 'IMAGE' }, { token: 'evidence-a' }, { token: '..' }, { token: null, kind: 'UNAVAILABLE' }],
      appealAttachments: ['appeal-b'],
      images: '/file/old-a.png,/file/old-b.png'
    }
  })
  assert.deepEqual(groups.attachments, [{ token: 'evidence-a', kind: 'IMAGE' }, { token: '', kind: 'UNAVAILABLE' }])
  assert.deepEqual(groups.appealAttachments, [{ token: 'appeal-b', kind: '' }])
  assert.equal(groups.legacyImageCount, 0)

  const legacy = api.complaintAttachmentGroups({ complaint: { images: '/file/old-a.png,/file/old-b.png' } })
  assert.deepEqual(legacy.attachments, [])
  assert.equal(legacy.legacyImageCount, 2)
})

test('current CS token is sent in Authorization and failures become unavailable', async () => {
  const originalFetch = globalThis.fetch
  const calls = []
  try {
    globalThis.fetch = async (url, options) => {
      calls.push({ url, options })
      if (url.endsWith('/attachment/forbidden')) {
        return { ok: false, status: 403 }
      }
      return {
        ok: true,
        status: 200,
        blob: async () => new Blob(['private-bytes'], { type: 'image/png' })
      }
    }

    const blob = await api.fetchCsComplaintAttachment(17, 'token/a')
    assert.equal(await blob.text(), 'private-bytes')
    assert.equal(calls[0].url, '/api/cs/complaint/17/attachment/token%2Fa')
    assert.equal(calls[0].options.headers.Authorization, 'Bearer cs-current-token')
    assert.equal(calls[0].options.credentials, 'same-origin')
    assert.equal(calls[0].options.cache, 'no-store')

    await assert.rejects(
      api.fetchCsComplaintAttachment(17, 'forbidden'),
      error => error.message === '附件不可用' && error.status === 403
    )
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('admin complaint detail renders only blob/private attachment state and releases it', async () => {
  const page = await readFile(new URL('../src/views/complaint/ComplaintList.vue', import.meta.url), 'utf8')
  const apiSource = await readFile(new URL('../src/api/complaint-attachment.js', import.meta.url), 'utf8')
  assert.match(page, /fetchCsComplaintAttachment/)
  assert.match(page, /URL\.createObjectURL\(blob\)/)
  assert.match(page, /URL\.revokeObjectURL/)
  assert.match(page, /@closed="handleDetailClosed"/)
  assert.match(page, /onBeforeUnmount/)
  assert.match(page, /附件不可用/)
  assert.match(page, /UNAVAILABLE/)
  assert.match(page, /旧版图片不可直接访问/)
  assert.match(page, /:src="att\.src"/)
  assert.doesNotMatch(page, /complaint\.images|\.images\.split/)
  assert.doesNotMatch(page, /:src="[^"]*token/i)
  // The shared axios client expects JSON envelopes; binary reads bypass it deliberately.
  assert.match(apiSource, /await fetch\(url,/)
  assert.doesNotMatch(apiSource, /@\/utils\/request|from ['"]axios['"]/)
})
