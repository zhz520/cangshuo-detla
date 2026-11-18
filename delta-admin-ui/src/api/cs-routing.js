import request from '@/utils/request'

export function csRoutingStaff() {
  return request({ url: '/admin/cs-routing/staff', method: 'get' })
}
export function csRoutingRules() {
  return request({ url: '/admin/cs-routing/rules', method: 'get' })
}
export function saveCsRoutingRule(data) {
  return request({ url: '/admin/cs-routing/rules', method: 'post', data })
}
export function saveCsRoutingRules(data) {
  return request({ url: '/admin/cs-routing/rules/batch', method: 'post', data })
}
export function deleteCsRoutingRule(id) {
  return request({ url: '/admin/cs-routing/rules/' + id, method: 'delete' })
}
export function csRoutingGroups() {
  return request({ url: '/admin/cs-routing/groups', method: 'get' })
}
export function saveCsRoutingGroup(data) {
  return request({ url: '/admin/cs-routing/groups', method: 'post', data })
}
export function deleteCsRoutingGroup(id) {
  return request({ url: '/admin/cs-routing/groups/' + id, method: 'delete' })
}
export function csRatingStats() {
  return request({ url: '/admin/cs-routing/ratings', method: 'get' })
}
