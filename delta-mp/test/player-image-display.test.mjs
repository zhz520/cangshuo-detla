import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL(path, import.meta.url), 'utf8')

test('player cover and cards request retina-safe thumbnail widths', async () => {
  const detail = await read('../pages/players/detail.vue')
  assert.match(detail, /<AppImage class="cover-main" :width="1080" eager[^>]+mode="aspectFill"/)
  assert.doesNotMatch(detail, /cover-backdrop/)
  assert.match(detail, /\.cover \{ height:375rpx; overflow:hidden;/)
  assert.match(detail, /\.profile-card \{ margin:-64rpx 24rpx 0; z-index:2; \}/)
  assert.match(detail, /\.avatar \{ margin-top:-78rpx; \}/)

  const card = await read('../components/PlayerShowcaseCard.vue')
  assert.match(card, /<AppImage :width="320"[^>]*:src="player\.avatar/)
  assert.doesNotMatch(card, /player\.coverImage/)

  const listCard = await read('../components/PlayerListCard.vue')
  assert.match(listCard, /<AppImage :width="320"[^>]*:src="player\.avatar/)
  assert.doesNotMatch(listCard, /player\.coverImage/)
})

test('player editors explain the recommended upload ratios', async () => {
  const apply = await read('../pages-player/apply/index.vue')
  assert.match(apply, /1:1 方图，建议 640×640 像素以上/)

  const homepage = await read('../pages-player/profile/introduction.vue')
  assert.match(homepage, /页面会统一居中裁切为 21:9 展示/)
  assert.match(homepage, /1:1 方图，建议 1000×1000 像素以上/)
})
