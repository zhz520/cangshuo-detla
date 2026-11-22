// Readiness must come from the persisted provider, not an unsaved selection.
export function serviceAccountState(kind, saved, selectedProvider) {
  if (!saved) return 'LOADING'
  if (kind === 'sms' && selectedProvider !== saved.provider) return 'UNSAVED'
  if (kind === 'sms' && saved.provider === 'ALIYUN') return 'DIRECT'
  return saved.configured ? 'CONNECTED' : 'UNCONNECTED'
}
