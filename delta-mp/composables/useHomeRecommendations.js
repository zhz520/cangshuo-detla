import { ref } from 'vue'

export function useHomeRecommendations({ getRecommendProducts, getRecommendCategories, productSortParams }) {
  const recommendProducts = ref([])
  const recommendLoading = ref(true)
  const recommendError = ref('')
  /** 热门推荐 Tab：全部 + 接口返回的分类（护航专区推荐、热门推荐等） */
  const recommendTabs = ref([])
  const currentRecommendCategoryId = ref('')
  const currentSort = ref('default')
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
      if (request === recommendRequest) recommendProducts.value = res.data?.records || res.data || []
    } catch {
      if (request === recommendRequest) {
        recommendError.value = '推荐商品加载失败，请重试。'
      }
    } finally {
      if (request === recommendRequest) recommendLoading.value = false
    }
  }
  return { recommendProducts, recommendLoading, recommendError, recommendTabs, currentRecommendCategoryId, currentSort, showHomeActions, switchRecommendCategory, chooseHomeSort, loadRecommendProducts, loadRecommendations }
}
