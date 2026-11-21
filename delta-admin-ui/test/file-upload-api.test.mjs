import test from 'node:test'
import assert from 'node:assert/strict'
import { register } from 'node:module'

const asModule = source => 'data:text/javascript,' + encodeURIComponent(source)
const requestMock = asModule(`
  export const calls = []
  export const response = Promise.resolve({ data: '/file/example' })
  export default function request(config) {
    calls.push(config)
    return response
  }
`)

// Stub only the Vite request alias; load the API modules and their exports as ESM.
register(asModule(`
  export function resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier === '@/utils/request' ? ${JSON.stringify(requestMock)} : specifier,
      context
    )
  }
`), import.meta.url)

const business = await import('../src/api/business.js')
const fileUpload = await import('../src/api/file-upload.js')
const { calls, response } = await import(requestMock)

test('business keeps the same named exports as the file upload module', () => {
  assert.equal(business.uploadFile, fileUpload.uploadFile)
  assert.equal(business.uploadProfileAudio, fileUpload.uploadProfileAudio)
})

for (const [name, url, filename, mimeType] of [
  ['uploadFile', '/common/file/upload', 'sample.png', 'image/png'],
  ['uploadProfileAudio', '/common/file/profile-audio', 'sample.mp3', 'audio/mpeg']
]) {
  test(`${name} sends the original file as the only multipart field`, () => {
    const file = new File(['sample'], filename, { type: mimeType })
    assert.equal(business[name](file), response)

    assert.equal(calls.length, 1)
    const config = calls.shift()
    const { data, ...options } = config
    assert.deepEqual(options, {
      url,
      method: 'post',
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    assert.ok(data instanceof FormData)
    assert.deepEqual([...data.keys()], ['file'])
    assert.equal(data.get('file'), file)
  })
}
