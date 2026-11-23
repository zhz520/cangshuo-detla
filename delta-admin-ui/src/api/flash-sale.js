import request from '@/utils/request'

export function getFlashSales() {
  return request({ url: '/admin/flash-sales', method: 'get' })
}

export function saveFlashSale(id, data) {
  return request({ url: id ? `/admin/flash-sales/${id}` : '/admin/flash-sales', method: id ? 'put' : 'post', data })
}

export function deleteFlashSale(id) {
  return request({ url: `/admin/flash-sales/${id}`, method: 'delete', data: {} })
}
