import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { babelParse, compileScript, compileTemplate, parse } from '@vue/compiler-sfc'

async function read(path) {
  return readFile(new URL(`../${path}`, import.meta.url), 'utf8')
}

async function parseSfc(filename) {
  const source = await read(filename)
  const { descriptor, errors } = parse(source, { filename })
  assert.deepEqual(errors, [])
  compileScript(descriptor, { id: filename })
  const template = compileTemplate({ source: descriptor.template.content, filename, id: filename })
  assert.deepEqual(template.errors, [])
  babelParse(descriptor.scriptSetup.content, { sourceType: 'module' })
}

test('complaint pages use private attachment upload and submit attachmentIds', async () => {
  const create = await read('pages/complaint/create.vue')
  assert.match(create, /ComplaintAttachmentUploader/)
  assert.match(create, /attachmentIds: \[\.\.\.attachmentIds\.value\]/)
  assert.doesNotMatch(create, /\bimages\s*:/)

  const detail = await read('pages/complaint/detail.vue')
  assert.match(detail, /getComplaintAttachment/)
  assert.match(detail, /attachmentIds: \[\.\.\.appealAttachmentIds\.value\]/)
  assert.match(detail, /releaseComplaintAttachmentUrl/)
  assert.match(detail, /UNAVAILABLE/)
  assert.match(detail, /onUnload\(disposePage\)/)
  assert.doesNotMatch(detail, /complaint\.images|\.images\.split/)
  assert.doesNotMatch(detail, /:src="img"/)

  const csDetail = await read('pages-cs/complaint/handle.vue')
  assert.match(csDetail, /getCsComplaintAttachment/)
  assert.match(csDetail, /releaseCsComplaintAttachmentUrl/)
  assert.match(csDetail, /UNAVAILABLE/)
  assert.match(csDetail, /onUnload\(disposePage\)/)
  assert.doesNotMatch(csDetail, /complaint\.images|\.images\.split/)
  assert.doesNotMatch(csDetail, /:src="img"/)

  const csApi = await read('pages-cs/api/cs.js')
  assert.match(csApi, /\/cs\/complaint\/\$\{encodeURIComponent\(id\)\}\/attachment\/\$\{encodeURIComponent\(attachmentToken\)\}/)

  await parseSfc('pages/complaint/create.vue')
  await parseSfc('pages/complaint/detail.vue')
  await parseSfc('components/ComplaintAttachmentUploader.vue')
  await parseSfc('pages-cs/complaint/handle.vue')
})

test('complaint attachment API uses authenticated private endpoints and opaque ids', async () => {
  const calls = { upload: [], download: [], released: [] }
  globalThis.__complaintUserToken = 'logged-in-user'
  globalThis.__complaintRequestMock = {
    get() {}, post() {},
    uploadFile(filePath, endpoint, options) {
      calls.upload.push({ filePath, endpoint, options })
      return Promise.resolve({ token: 'token-uploaded' })
    },
    downloadPrivateFile(endpoint, options) {
      calls.download.push({ endpoint, options })
      return Promise.resolve('blob:local-private-preview')
    },
    releasePrivateFileUrl(source) { calls.released.push(source) }
  }
  const source = (await read('api/complaint.js')).replace(
    "import { downloadPrivateFile, get, post, releasePrivateFileUrl, uploadFile } from './request'",
    "const { downloadPrivateFile, get, post, releasePrivateFileUrl, uploadFile } = globalThis.__complaintRequestMock"
  ).replace(
    "import { getUserToken } from '@/utils/auth'",
    "const getUserToken = () => globalThis.__complaintUserToken"
  )
  const api = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))

  assert.deepEqual(api.normalizeAttachmentList([
    { token: 'a', kind: 'IMAGE' }, { token: 'a', kind: 'IMAGE' }, { token: '', kind: 'IMAGE' }, { token: '..' },
    { token: null, kind: 'UNAVAILABLE' }
  ]), [{ token: 'a', kind: 'IMAGE' }, { token: '', kind: 'UNAVAILABLE' }])
  assert.equal(await api.uploadComplaintAttachment(42, '/tmp/evidence.png'), 'token-uploaded')
  assert.deepEqual(calls.upload, [{
    filePath: '/tmp/evidence.png',
    endpoint: '/order/complaint/attachment?orderId=42',
    options: { role: 'user' }
  }])
  assert.equal(await api.getComplaintAttachment('token/with-slash'), 'blob:local-private-preview')
  assert.deepEqual(calls.download, [{
    endpoint: '/order/complaint/attachment/token%2Fwith-slash',
    options: { role: 'user' }
  }])
  globalThis.__complaintUserToken = ''
  await api.getComplaintAttachment('guest-token')
  assert.deepEqual(calls.download[1], {
    endpoint: '/order/complaint/attachment/guest-token',
    options: { role: 'guest' }
  })
  api.releaseComplaintAttachmentUrl('blob:local-private-preview')
  assert.deepEqual(calls.released, ['blob:local-private-preview'])
})

async function requestHarness() {
  const loadingSource = await read('utils/loading.js')
  const storage = new Map([['app_role', 'user'], ['user_token', 'user-private-token']])
  globalThis.__requestTestStorage = storage
  globalThis.getCurrentPages = () => []
  globalThis.uni = {
    request: options => globalThis.__privateRequests.push(options),
    showToast() {}, showLoading() {}, hideLoading() {}, reLaunch() {}
  }
  globalThis.__privateRequests = []
  const source = (await read('api/request.js'))
    .replace("import { clientStorage } from '@/utils/client-storage'", "const clientStorage = { get: key => globalThis.__requestTestStorage.get(key), set: (key,v) => globalThis.__requestTestStorage.set(key,v), remove: key => globalThis.__requestTestStorage.delete(key) }")
    .replace("import { getCurrentRole, getTokenByRole, removeGuestSession } from '@/utils/auth'", "const getTokenByRole = role => clientStorage.get(role + '_token') || ''; const getCurrentRole = () => 'user'; const removeGuestSession = () => {}")
    .replace("import { createLoadingController } from '@/utils/loading'", loadingSource.replace('export function', 'function'))
    .replaceAll('import.meta.env', '({ DEV:false })')
  const module = await import('data:text/javascript;base64,' + Buffer.from(source + '\n// ' + Math.random()).toString('base64'))
  return { ...module, requests: globalThis.__privateRequests, storage }
}

test('private download uses Authorization without a public URL fallback', async () => {
  const originalWindow = globalThis.window
  globalThis.window = {}
  try {
    const h = await requestHarness()
    const pending = h.downloadPrivateFile('/order/complaint/attachment/token-a', { role: 'user' })
    assert.equal(h.requests.length, 1)
    assert.equal(h.requests[0].responseType, 'arraybuffer')
    assert.equal(h.requests[0].header.Authorization, 'Bearer user-private-token')
    assert.equal(new URL(h.requests[0].url).search, '')
    h.requests[0].success({
      statusCode: 200,
      data: new Uint8Array([1, 2, 3]).buffer,
      header: { 'content-type': 'image/png' }
    })
    const source = await pending
    assert.match(source, /^blob:/)
    h.releasePrivateFileUrl(source)

    delete globalThis.window
    const downloads = []
    globalThis.uni.downloadFile = options => downloads.push(options)
    const temporary = h.downloadPrivateFile('/order/complaint/attachment/token-b', { role: 'user' })
    assert.equal(downloads.length, 1)
    assert.equal(downloads[0].header.Authorization, 'Bearer user-private-token')
    assert.equal(new URL(downloads[0].url).search, '')
    downloads[0].success({ statusCode: 200, tempFilePath: 'wxfile://tmp/private-evidence' })
    assert.equal(await temporary, 'wxfile://tmp/private-evidence')

    delete globalThis.uni.downloadFile
    await assert.rejects(
      h.downloadPrivateFile('/order/complaint/attachment/token-c', { role: 'user' }),
      error => error.attachmentUnsupported === true
    )
  } finally {
    globalThis.window = originalWindow
    if (originalWindow === undefined) delete globalThis.window
  }
})
