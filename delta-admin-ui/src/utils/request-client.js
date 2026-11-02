// Share concurrent reads only. Completed responses are never cached, and a write
// starts a new generation so a subsequent refresh cannot reuse an older read.
export function createRequestClient(send, readKey, { start = () => {}, finish = () => {} } = {}) {
  const pending = new Map()
  let generation = 0

  function request(config) {
    const method = (config.method || 'get').toLowerCase()
    if (!['get', 'head'].includes(method)) generation++
    const share = method === 'get' && config.dedupe !== false && !config.signal && !config.cancelToken
      && !config.adapter && !config.transformResponse && !config.transformRequest
      && !config.onDownloadProgress && !config.paramsSerializer && !config.validateStatus
    const key = share ? `${generation}:${readKey(config)}` : null
    if (key && pending.has(key)) return pending.get(key)

    start()
    let result
    try { result = send(config) } catch (error) { result = Promise.reject(error) }
    const promise = Promise.resolve(result).finally(() => {
      if (key) pending.delete(key)
      finish()
    })
    if (key) pending.set(key, promise)
    return promise
  }

  request.request = request
  for (const method of ['get', 'head', 'delete', 'options']) {
    request[method] = (url, config = {}) => request({ ...config, method, url })
  }
  for (const method of ['post', 'put', 'patch']) {
    request[method] = (url, data, config = {}) => request({ ...config, method, url, data })
  }
  return request
}
