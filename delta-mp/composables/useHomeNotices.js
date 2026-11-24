import { computed, ref } from 'vue'

export function useHomeNotices({ siteStore, clientStorage, getActiveNotices, productDescriptionHtml }) {
  const notices = ref([])
  const showNoticePopup = ref(false)
  const popupNotices = ref([])
  const popupCurrentIndex = ref(0)
  const noticeHtml = computed(() => productDescriptionHtml(popupNotices.value[popupCurrentIndex.value]?.content || ''))
  const showNoticeList = ref(false)

  function noticeRevision(notice) {
    // A new admin revision (content, status or popup toggle) gets a new acknowledgement key.
    return `${notice.id}:${notice.updatedAt || notice.createdAt || ''}`
  }

  function loadNotices(opts, config) {
    return getActiveNotices(opts).then(async res => {
      const list = res.data?.records || res.data
      notices.value = Array.isArray(list) ? list : []
      // Wait for the current public setting before deciding whether a dismissed
      // notice should be shown again on this page entry.
      await config
      if (!showNoticePopup.value && notices.value.length) {
        // 筛选出需要弹窗展示的公告
        const popupList = notices.value.filter(n => n.popupDisplay === 1)
        if (popupList.length > 0) {
          const seenIds = clientStorage.get('seenNoticeIds') || []
          const unseenList = popupList.filter(n => !seenIds.includes(noticeRevision(n)))
          // In every-refresh mode one JS session may render the home tab many times;
          // switching tabs must not reopen it. A real browser refresh recreates the store.
          const displayList = siteStore.noticePopupEveryVisit
            ? (siteStore.hasShownNoticePopupThisSession() ? [] : popupList)
            : unseenList
          if (displayList.length > 0) {
            siteStore.markNoticePopupShownThisSession()
            popupNotices.value = displayList
            popupCurrentIndex.value = 0
            showNoticePopup.value = true
          }
        }
      }
    })
  }

  function openNoticeDetail(item) {
    popupNotices.value = [item]
    popupCurrentIndex.value = 0
    showNoticePopup.value = true
  }

  function closeNoticePopup() {
    showNoticePopup.value = false
    // 记录已看过的弹窗公告ID
    const seenIds = clientStorage.get('seenNoticeIds') || []
    popupNotices.value.forEach(n => {
      const revision = noticeRevision(n)
      if (n.id && !seenIds.includes(revision)) {
        seenIds.push(revision)
      }
    })
    clientStorage.set('seenNoticeIds', seenIds.slice(-100))
  }
  return { notices, showNoticeList, popupCurrentIndex, popupNotices, showNoticePopup, noticeHtml, openNoticeDetail, closeNoticePopup, loadNotices }
}
