import { ref } from 'vue'
import { clientStorage } from '../utils/client-storage.js'

const CACHE_KEY = 'home-recommend-v1'

export function useHomeRecommendations({ getRecommendProducts, getRecommendCategories, productSortParams }) {
  const cached = (() => { try { return clientStorage.get(CACHE_KEY) } catch (_) { return null } })()
  const recommendProducts = ref(Array.isArray(cached?.products) ? cached.products : [])
  const recommendLoading = ref(!recommendProducts.value.length)
  const recommendError = ref('')
  /** 热门推荐 Tab：全部 + 接口返回的分类（护航专区推荐、热门推荐等） */
  const recommendTabs = ref(Array.isArray(cached?.tabs) ? cached.tabs : [])
  const currentRecommendCategoryId = ref(String(cached?.categoryId || ''))
  const currentSort = ref(String(cached?.sort || 'default'))
  const showHomeActions = ref(false)
  let recommendRequest = 0
  let catalogRequest = 0
  function loadRecommendations(opts) {
    const catalog = ++catalogRequest
    return getRecommendCategories(opts).then(res => {
      if (catalog === catalogRequest && Array.isArray(res.data)) {
        recommendTabs.value = res.data.map(c => ({ id: String(c.id), name: c.name }))
        if (!recommendTabs.value.some(tab => tab.id === currentRecommendCategoryId.value)) {
          const next = recommendTabs.value[0]?.id || ''
          if (currentRecommendCategoryId.value !== next) recommendProducts.value = []
          currentRecommendCategoryId.value = next
        }
        persist()
      }
    }).catch(() => {}).then(() => catalog === catalogRequest && loadRecommendProducts())
  }

  function switchRecommendCategory(id) {
    if (currentRecommendCategoryId.value === id) return
    recommendProducts.value = []
    currentRecommendCategoryId.value = id
    loadRecommendProducts()
  }

  function chooseHomeSort(value) {
    if (currentSort.value !== value) recommendProducts.value = []
    currentSort.value = value
    showHomeActions.value = false
    loadRecommendProducts()
  }

  async function loadRecommendProducts() {
    const request = ++recommendRequest
    recommendLoading.value = true
    recommendError.value = ''
    const params = { ...productSortParams(currentSort.value) }
    if (currentRecommendCategoryId.value) params.categoryId = currentRecommendCategoryId.value
    try {
      const res = await getRecommendProducts(params, { loading: false, silent: true })
      if (request === recommendRequest) { recommendProducts.value = res.data?.records || res.data || []; persist() }
    } catch {
      if (request === recommendRequest) {
        recommendError.value = '推荐商品加载失败，请重试。'
      }
    } finally {
      if (request === recommendRequest) recommendLoading.value = false
    }
  }
  function persist() {
    try { clientStorage.set(CACHE_KEY, { products: recommendProducts.value, tabs: recommendTabs.value, categoryId: currentRecommendCategoryId.value, sort: currentSort.value, at: Date.now() }) } catch (_) {}
  }
  return { recommendProducts, recommendLoading, recommendError, recommendTabs, currentRecommendCategoryId, currentSort, showHomeActions, switchRecommendCategory, chooseHomeSort, loadRecommendProducts, loadRecommendations }
}
