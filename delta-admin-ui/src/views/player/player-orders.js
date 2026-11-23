// A player detail dialog must never fall back to an unfiltered staff order list.
export async function readPlayerOrders(playerId, { pageNum, pageSize }, list) {
  if (!/^\d+$/.test(String(playerId)) || Number(playerId) <= 0) throw new Error('缺少打手编号')
  const { data } = await list({ playerId, pageNum, pageSize }, { silent: true })
  return { records: data?.records || [], total: Number(data?.total || 0) }
}
