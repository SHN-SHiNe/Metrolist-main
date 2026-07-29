import { expect, test } from '@playwright/test'

test('app shell exposes navigation and playback controls', async ({ page }) => {
  await page.route('**/api/**', async (route) => {
    const url = route.request().url()
    const body = url.includes('/library') ? { items: [], total: 0, offset: 0, limit: 50, revision: 1 } : []
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '首页' })).toBeVisible()
  await expect(page.locator('nav:visible').first()).toBeVisible()
  await expect(page.getByText('选择一首歌开始播放')).toBeVisible()
  await expect(page.locator('.mobile-nav .nav-button')).toHaveCount(4)
  await expect(page.locator('.mobile-nav')).toContainText('首页搜索音乐库本地')
  await expect(page.locator('.mobile-nav')).not.toContainText('同步')
})

test('large library loads pages while keeping the rendered row count bounded', async ({ page }) => {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/scans' && route.request().method() === 'POST') {
      await new Promise((resolve) => setTimeout(resolve, 400))
      return route.fulfill({ contentType: 'application/json', body: '{}' })
    }
    if (url.pathname === '/api/library') {
      const offset = Number(url.searchParams.get('offset') ?? 0)
      const query = url.searchParams.get('q')?.toLowerCase() ?? ''
      if (offset > 0) await new Promise((resolve) => setTimeout(resolve, 400))
      const all = Array.from({ length: 450 }, (_, index) => {
        const id = String(index + 1)
        return { id, title: `歌曲 ${id}`, artist: '测试歌手', album: '大曲库', durationMs: 180_000 }
      })
      const filtered = all.filter((track) => `${track.title} ${track.artist} ${track.album}`.toLowerCase().includes(query))
      const items = filtered.slice(offset, offset + 200)
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items, total: filtered.length, offset, limit: 200, revision: 1 }) })
    }
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/')
  await page.locator('.nav-button:visible').filter({ hasText: '本地' }).click()

  await expect(page.getByText('已加载 200 / 450 首')).toBeVisible()
  expect(await page.locator('.track-row').count()).toBeLessThan(50)
  await page.getByRole('button', { name: /继续加载/ }).click()
  await page.getByPlaceholder('搜索整个 NAS 曲库').fill('歌曲 450')
  await expect(page.getByText('1 首匹配')).toBeVisible()
  await expect(page.getByText('歌曲 450')).toBeVisible()
  await page.waitForTimeout(500)
  await expect(page.getByText('已加载 1 / 1 首')).toBeVisible()
  await page.getByPlaceholder('搜索整个 NAS 曲库').fill('')
  await expect(page.getByText('已加载 200 / 450 首')).toBeVisible()
  await page.getByRole('button', { name: /继续加载/ }).click()
  await expect(page.getByText('已加载 400 / 450 首')).toBeVisible()
  expect(await page.locator('.track-row').count()).toBeLessThan(50)
  await page.getByRole('button', { name: '重新扫描' }).click()
  await page.getByPlaceholder('搜索整个 NAS 曲库').fill('歌曲 450')
  await expect(page.getByText('已加载 1 / 1 首')).toBeVisible()
  await page.waitForTimeout(500)
  await expect(page.getByText('歌曲 450')).toBeVisible()
})

test('virtualized library remains reachable with list navigation keys', async ({ page }) => {
  const tracks = Array.from({ length: 200 }, (_, index) => ({ id: `keyboard-${index}`, title: `键盘歌曲 ${index + 1}`, artist: '测试歌手', album: '大曲库', durationMs: 180_000 }))
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/library') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: tracks, total: tracks.length, offset: 0, limit: 200, revision: 1 }) })
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/#local')
  const viewport = page.getByLabel('曲目列表，使用方向键、翻页键、首页键和末页键浏览')
  await expect(viewport).toHaveAttribute('tabindex', '0')
  await viewport.focus()
  await page.keyboard.press('ArrowDown')
  await expect(page.locator('[data-track-index="1"]')).toBeFocused()
  await page.keyboard.press('PageDown')
  await expect.poll(async () => page.locator('.track-viewport').evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
  await page.keyboard.press('End')
  await expect(page.locator('[data-track-index="199"]')).toBeFocused()
  await expect.poll(() => page.locator('.track-viewport').evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
  await page.keyboard.press('Home')
  await expect(page.locator('[data-track-index="0"]')).toBeFocused()
  await expect.poll(() => page.locator('.track-viewport').evaluate((element) => element.scrollTop)).toBe(0)
})

test('track actions open by touch hold and desktop context menu without accidental playback', async ({ page }) => {
  await page.setViewportSize({ width: 412, height: 915 })
  const track = { id: 'menu-track', title: '长按歌曲', artist: '家庭歌手', album: 'NAS 曲库', durationMs: 180_000 }
  let favorite = false
  let historyPosts = 0
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/library') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [track], total: 1, offset: 0, limit: 200, revision: 1 }) })
    if (url.pathname === '/api/favorites' && route.request().method() === 'GET') return route.fulfill({ contentType: 'application/json', body: JSON.stringify(favorite ? [track] : []) })
    if (url.pathname === `/api/favorites/${track.id}` && route.request().method() === 'PUT') { favorite = true; return route.fulfill({ status: 204 }) }
    if (url.pathname === '/api/history' && route.request().method() === 'POST') { historyPosts++; return route.fulfill({ status: 204 }) }
    if (url.pathname.startsWith('/api/media/')) return route.fulfill({ status: 206, contentType: 'audio/mpeg', body: '' })
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/#local')
  const row = page.locator('.track-row').first()
  await row.dispatchEvent('pointerdown', { pointerType: 'touch', pointerId: 7, button: 0, clientX: 120, clientY: 240 })
  await expect(page.getByRole('menu', { name: '长按歌曲 的操作' })).toBeVisible({ timeout: 3000 })
  await row.dispatchEvent('pointerup', { pointerType: 'touch', pointerId: 7, button: 0, clientX: 120, clientY: 240 })
  expect(historyPosts).toBe(0)
  await page.getByRole('menuitem', { name: '收藏', exact: true }).click()
  await expect.poll(() => favorite).toBe(true)
  await page.locator('.track-identity').first().click({ force: true })
  expect(historyPosts).toBe(0)

  await page.waitForTimeout(950)
  await page.setViewportSize({ width: 1366, height: 768 })
  await row.click({ button: 'right' })
  await expect(page.locator('.track-context-menu.context')).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '下一首播放' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '加入播放队列' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '添加到播放列表' })).toBeDisabled()
  await expect(page.getByRole('menuitem', { name: '分享' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '查看歌手' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '查看专辑' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '分析音乐画像' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '取消收藏', exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.locator('.track-context-menu')).toHaveCount(0)
  await expect(row).toBeFocused()
})

test('track menu actions mutate the queue, shared playlist and analysis queue', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 })
  const tracks = ['当前歌曲', '原下一首', '指定下一首'].map((title, index) => ({ id: `action-${index}`, title, artist: '动作歌手', album: '动作专辑', durationMs: 180_000 }))
  let playlistBody: { trackIds: string[]; expectedVersion: number } | null = null
  let analyzedIds: string[] = []
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const method = route.request().method()
    if (url.pathname === '/api/library') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: tracks, total: tracks.length, offset: 0, limit: 200, revision: 1 }) })
    if (url.pathname === '/api/playlists' && method === 'GET') return route.fulfill({ contentType: 'application/json', body: JSON.stringify([{ id: 'action-list', name: '动作歌单', version: 3, trackCount: 0, updatedAt: 1 }]) })
    if (url.pathname === '/api/playlists/action-list' && method === 'GET') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ id: 'action-list', name: '动作歌单', version: 3, tracks: [], updatedAt: 1 }) })
    if (url.pathname === '/api/playlists/action-list' && method === 'PUT') {
      playlistBody = route.request().postDataJSON() as { trackIds: string[]; expectedVersion: number }
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ id: 'action-list', name: '动作歌单', version: 4, tracks: [tracks[2]], updatedAt: 2 }) })
    }
    if (url.pathname === '/api/analysis' && method === 'POST') {
      analyzedIds = (route.request().postDataJSON() as { trackIds: string[] }).trackIds
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ queued: 1, draining: false }) })
    }
    if (url.pathname === '/api/tracks') return route.fulfill({ contentType: 'application/json', body: JSON.stringify([{ ...tracks[0], analysis: { status: 'completed', progress: 1 } }]) })
    if (url.pathname.endsWith('/similar')) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ seed: tracks[0], items: [] }) })
    if (url.pathname === '/api/history' && method === 'POST') return route.fulfill({ status: 204 })
    if (url.pathname.startsWith('/api/media/')) return route.fulfill({ status: 206, contentType: 'audio/mpeg', body: '' })
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/#local')
  await page.getByRole('button', { name: '播放 当前歌曲' }).click()
  const targetRow = page.locator('.track-row').filter({ hasText: '指定下一首' })
  await targetRow.click({ button: 'right' })
  await page.getByRole('menuitem', { name: '下一首播放' }).click()
  await expect(page.locator('.queue-editor-item').nth(1)).toContainText('指定下一首')

  await targetRow.click({ button: 'right' })
  await page.getByRole('menuitem', { name: '添加到播放列表' }).click()
  await page.getByRole('menuitem', { name: '动作歌单' }).click()
  await expect.poll(() => playlistBody?.trackIds.join(',')).toBe('action-2')
  expect(playlistBody?.expectedVersion).toBe(3)

  const firstRow = page.locator('.track-row').filter({ hasText: '当前歌曲' })
  await firstRow.click({ button: 'right' })
  await page.getByRole('menuitem', { name: '分析音乐画像' }).click()
  await expect.poll(() => analyzedIds.join(',')).toBe('action-0')
})

test('library sorting sends energy, modified time and explicit direction', async ({ page }) => {
  const track = { id: 'sort-track', title: '排序歌曲', artist: '歌手', album: '专辑', durationMs: 180_000 }
  const requests: URL[] = []
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/library') {
      requests.push(url)
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [track], total: 1, offset: 0, limit: 200, revision: 1 }) })
    }
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/#local')
  await page.getByLabel('曲库排序').selectOption('energy')
  await expect.poll(() => requests.at(-1)?.searchParams.get('sort')).toBe('energy')
  await page.getByRole('button', { name: /当前升序，点击切换为降序/ }).click()
  await expect.poll(() => requests.at(-1)?.searchParams.get('direction')).toBe('desc')
  await page.getByLabel('曲库排序').selectOption('scanned')
  await expect.poll(() => requests.at(-1)?.searchParams.get('sort')).toBe('scanned')
  await page.getByLabel('曲库排序').selectOption('modified')
  await expect.poll(() => requests.at(-1)?.searchParams.get('sort')).toBe('modified')
})

test('joining a room aborts pending similar continuation before it can change the local queue', async ({ page }) => {
  await page.addInitScript(() => {
    const NativeAudio = window.Audio
    const state = window as typeof window & { __shineTestAudio?: HTMLAudioElement }
    const WrappedAudio = function (src?: string) {
      const audio = new NativeAudio(src)
      if (!state.__shineTestAudio) state.__shineTestAudio = audio
      return audio
    }
    WrappedAudio.prototype = NativeAudio.prototype
    Object.defineProperty(window, 'Audio', { configurable: true, writable: true, value: WrappedAudio })
  })
  const seed = { id: 'similar-seed', title: '种子歌曲', artist: '家庭歌手', album: 'NAS 曲库', durationMs: 180_000, analysis: { status: 'completed', progress: 1 } }
  const continuation = { id: 'similar-next', title: '不应插入', artist: '家庭歌手', album: 'NAS 曲库', durationMs: 180_000, analysis: { status: 'completed', progress: 1 } }
  const room = { id: 'race-room', name: '竞态房间', memberCount: 0, version: 0, updatedAt: 1 }
  let releaseSimilar: (() => void) | undefined
  const similarGate = new Promise<void>((resolve) => { releaseSimilar = resolve })
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/library') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [seed], total: 1, offset: 0, limit: 200, revision: 1 }) })
    if (url.pathname === `/api/library/${seed.id}/similar`) {
      await similarGate
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ seed, items: [{ track: continuation, similarityPercent: 95 }] }) }).catch(() => undefined)
      return
    }
    if (url.pathname === '/api/rooms' && route.request().method() === 'GET') return route.fulfill({ contentType: 'application/json', body: JSON.stringify([room]) })
    if (url.pathname === `/api/rooms/${room.id}`) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ summary: room, state: { queue: [], currentTrackId: null, positionMs: 0, playing: false, effectiveAt: 0 } }) })
    if (url.pathname === '/api/history' && route.request().method() === 'POST') return route.fulfill({ status: 204 })
    if (url.pathname.startsWith('/api/media/')) return route.fulfill({ status: 206, contentType: 'audio/mpeg', body: '' })
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/#local')
  await page.getByRole('button', { name: '播放 种子歌曲' }).first().click()
  const similarRequest = page.waitForRequest((request) => request.url().includes(`/api/library/${seed.id}/similar`))
  await page.evaluate(() => (window as typeof window & { __shineTestAudio?: HTMLAudioElement }).__shineTestAudio?.dispatchEvent(new Event('ended')))
  await similarRequest
  await page.evaluate(() => { location.hash = 'rooms' })
  await page.getByRole('button', { name: '加入并启用声音' }).click()
  await expect(page.getByRole('button', { name: '离开' })).toBeVisible()
  releaseSimilar?.()
  await page.waitForTimeout(350)
  await expect(page.locator('.queue-editor-item')).toHaveCount(1)
  await expect(page.locator('.queue-editor-item')).toContainText('种子歌曲')
  await expect(page.locator('.queue-editor-item')).not.toContainText('不应插入')
})

test('music libraries are visible in settings and filter the NAS catalog', async ({ page }) => {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/libraries') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify([
        { id: 'default', name: 'NAS 音乐', path: '/music', deviceType: 'local', readOnly: false, enabled: true, downloadTarget: true, status: 'online', trackCount: 1, createdAt: 1, updatedAt: 1 },
        { id: 'usb', name: '随身硬盘', path: '/libraries/usb', deviceType: 'usb', readOnly: true, enabled: true, downloadTarget: false, status: 'offline', trackCount: 1, lastError: 'path_unavailable', createdAt: 2, updatedAt: 2 },
      ]) })
    }
    if (url.pathname === '/api/library') {
      const all = [
        { id: 'local', title: '本地歌曲', artist: '歌手', album: '', durationMs: 1000, libraryId: 'default' },
        { id: 'portable', title: '移动歌曲', artist: '歌手', album: '', durationMs: 1000, libraryId: 'usb' },
      ]
      const libraryId = url.searchParams.get('libraryId')
      const items = libraryId ? all.filter((track) => track.libraryId === libraryId) : all
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items, total: items.length, offset: 0, limit: 200, revision: 1 }) })
    }
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/#settings')
  await page.getByRole('button', { name: /音频库/ }).click()
  await expect(page.getByRole('heading', { name: '音频库' })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '随身硬盘 的名称' })).toHaveValue('随身硬盘')
  await expect(page.getByText('设备离线')).toBeVisible()

  await page.goto('/#local')
  await page.getByLabel('按音频库筛选').selectOption('usb')
  await expect(page.getByText('移动歌曲')).toBeVisible()
  await expect(page.getByText('本地歌曲')).not.toBeVisible()
})

test('sync room creation works when HTTP does not expose crypto.randomUUID', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: undefined })
  })
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/library') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [], total: 0, offset: 0, limit: 200, revision: 1 }) })
    }
    if (url.pathname === '/api/rooms' && route.request().method() === 'POST') {
      const body = route.request().postDataJSON() as { id: string; name: string }
      return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: body.id, name: body.name, memberCount: 0, version: 0, updatedAt: 1 }) })
    }
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/#rooms')
  const createRequest = page.waitForRequest((request) => request.url().endsWith('/api/rooms') && request.method() === 'POST')
  await page.getByRole('button', { name: '创建并加入' }).click()
  const body = (await createRequest).postDataJSON() as { id: string; name: string }
  expect(body.name).toBe('全屋同步')
  expect(body.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
})

test('a visitor can delete a shared sync room', async ({ page }) => {
  const room = { id: '00000000-0000-4000-8000-000000000099', name: '临时房间', memberCount: 0, version: 0, updatedAt: 1 }
  let deleted = false
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/library') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [], total: 0, offset: 0, limit: 200, revision: 1 }) })
    }
    if (url.pathname === '/api/rooms' && route.request().method() === 'GET') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify(deleted ? [] : [room]) })
    }
    if (url.pathname === `/api/rooms/${room.id}` && route.request().method() === 'DELETE') {
      deleted = true
      return route.fulfill({ status: 204 })
    }
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  page.on('dialog', (dialog) => void dialog.accept())
  await page.goto('/#rooms')
  await expect(page.getByText('临时房间')).toBeVisible()
  await page.getByRole('button', { name: '删除房间 临时房间' }).click()
  await expect(page.getByText('临时房间')).not.toBeVisible()
  await expect(page.getByText('同步房间已删除')).toBeVisible()
})

test('current track analysis updates the player without a page refresh', async ({ page }) => {
  const pending = { id: 'track-analysis', title: '等待分析', artist: 'SHiNe', album: '家庭精选', durationMs: 180000, analysis: { status: 'pending', progress: 0 } }
  const completed = { ...pending, analysis: { status: 'completed', progress: 1, bpm: 120, keyName: 'Am', camelot: '8A', valence: .6, energy: .7, danceability: .5, acousticness: .3, instrumentalness: .2, liveness: .1, speechiness: .08 } }
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    let body: unknown = []
    if (url.pathname === '/api/library') body = { items: [pending], total: 1, offset: 0, limit: 200, revision: 1 }
    else if (url.pathname === '/api/tracks') body = [completed]
    else if (url.pathname === '/api/analysis' && route.request().method() === 'POST') body = { queued: 1, draining: false }
    else if (url.pathname.endsWith('/similar')) body = { seed: completed, items: [] }
    else if (url.pathname === '/api/libraries') body = []
    else if (url.pathname.startsWith('/api/media/')) return route.fulfill({ status: 206, contentType: 'audio/mpeg', body: '' })
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/#local')
  await page.getByRole('button', { name: '播放 等待分析' }).click()
  const trigger = page.locator('.player-track')
  await trigger.click()
  await expect(page.getByRole('button', { name: '关闭' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '正在播放' })).toBeHidden()
  await expect(trigger).toBeFocused()
  await trigger.click()
  await page.getByRole('tab', { name: '音乐画像' }).click()
  await page.getByRole('button', { name: '分析这首歌' }).click()
  await expect(page.getByText('120 BPM')).toBeVisible()
  await expect(page.getByText('8A')).toBeVisible()
})

test('advanced analysis summary polls and reports the enqueue response', async ({ page }) => {
  let summaryRequests = 0
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    let body: unknown = []
    if (url.pathname === '/api/library') body = { items: [], total: 0, offset: 0, limit: 200, revision: 1 }
    else if (url.pathname === '/api/libraries') body = []
    else if (url.pathname === '/api/analysis' && route.request().method() === 'POST') body = { queued: 7, draining: false }
    else if (url.pathname === '/api/analysis') {
      summaryRequests++
      body = summaryRequests <= 2
        ? { available: true, implementation: 'vibenet', total: 8, pending: 8, queued: 0, running: 0, completed: 0, failed: 0 }
        : { available: true, implementation: 'vibenet', total: 8, pending: 0, queued: 0, running: 0, completed: 8, failed: 0 }
    }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/#search')
  await page.getByRole('tab', { name: '高级听感' }).click()
  await expect(page.locator('.analysis-progress strong')).toHaveText('8', { timeout: 7000 })
  expect(summaryRequests).toBeGreaterThanOrEqual(2)
  await page.getByRole('button', { name: '分析缺失曲目' }).click()
  await expect(page.getByText('7 首曲目已加入分析队列')).toBeVisible()
})

test('online preview asks for download and never enters the NAS analysis queue', async ({ page }) => {
  const online = { id: 'online-demo-1', title: '云端试听', artist: '在线音源', album: '搜索结果', durationMs: 180000, source: 'netease', analysis: { status: 'pending', progress: 0 } }
  let analysisPosts = 0
  let favoritePuts = 0
  let playlistPuts = 0
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    let body: unknown = []
    if (url.pathname === '/api/library') body = { items: [], total: 0, offset: 0, limit: 200, revision: 1 }
    else if (url.pathname === '/api/search') body = { items: [online], total: 1, page: 1, limit: 50 }
    else if (url.pathname === '/api/libraries') body = []
    else if (url.pathname === '/api/playlists' && route.request().method() === 'GET') body = [{ id: 'online-list', name: '云端收藏', version: 1, trackCount: 0, updatedAt: 1 }]
    else if (url.pathname === '/api/playlists/online-list' && route.request().method() === 'GET') body = { id: 'online-list', name: '云端收藏', version: 1, tracks: [], updatedAt: 1 }
    else if (url.pathname === '/api/playlists/online-list' && route.request().method() === 'PUT') { playlistPuts++; body = { id: 'online-list', name: '云端收藏', version: 2, tracks: [online], updatedAt: 2 } }
    else if (url.pathname === `/api/favorites/${online.id}` && route.request().method() === 'PUT') { favoritePuts++; return route.fulfill({ status: 204 }) }
    else if (url.pathname === '/api/analysis' && route.request().method() === 'POST') { analysisPosts++; body = { queued: 0, draining: false } }
    else if (url.pathname.startsWith('/api/media/')) return route.fulfill({ status: 206, contentType: 'audio/mpeg', body: '' })
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/#search')
  await page.getByLabel('在线搜索').fill('云端试听')
  await page.locator('.search-box').getByRole('button', { name: '搜索', exact: true }).click()
  const onlineRow = page.locator('.track-row').filter({ hasText: '云端试听' })
  await onlineRow.click({ button: 'right' })
  await expect(page.getByRole('menuitem', { name: '收藏', exact: true })).toBeEnabled()
  await page.getByRole('menuitem', { name: '收藏', exact: true }).click()
  await expect.poll(() => favoritePuts).toBe(1)
  await onlineRow.click({ button: 'right' })
  await expect(page.getByRole('menuitem', { name: '添加到播放列表' })).toBeEnabled()
  await page.getByRole('menuitem', { name: '添加到播放列表' }).click()
  await page.getByRole('menuitem', { name: '云端收藏' }).click()
  await expect.poll(() => playlistPuts).toBe(1)
  const identity = page.locator('.track-identity').filter({ hasText: '云端试听' })
  const onlineDialog = page.getByRole('dialog', { name: '是否播放这首网易云歌曲？' })
  await identity.click()
  await expect(onlineDialog.getByRole('button', { name: '取消' })).toBeFocused()
  await page.getByLabel('在线搜索').evaluate((input: HTMLInputElement) => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, '云端试听更新')
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
  await expect(onlineDialog.getByRole('button', { name: '取消' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(identity).toBeFocused()

  await onlineRow.click({ button: 'right' })
  await page.getByRole('menuitem', { name: '播放', exact: true }).click()
  await expect(onlineDialog).toBeVisible()
  await onlineDialog.getByRole('button', { name: '播放' }).click()
  await page.locator('.player-track').click()
  await page.getByRole('tab', { name: '音乐画像' }).click()
  await page.getByRole('button', { name: '先下载入库后分析' }).click()
  await expect(page.getByText('在线临时歌曲需先下载入 NAS 曲库，再进行音乐画像分析')).toBeVisible()
  expect(analysisPosts).toBe(0)
  await page.keyboard.press('Escape')
  await page.evaluate(() => { location.hash = 'local' })
  await expect(page.getByRole('heading', { name: '本地' })).toBeVisible()
  expect(await page.locator('.track-list').textContent()).not.toContain('云端试听')
})

test('download notification opens a live queue on mobile and the queue remains visible on desktop', async ({ page }) => {
  await page.setViewportSize({ width: 412, height: 915 })
  const online = { id: 'download-demo', title: '正在回家', artist: '在线歌手', album: '搜索结果', durationMs: 180000, source: 'netease' }
  let jobs: unknown[] = []
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const method = route.request().method()
    let body: unknown = []
    if (url.pathname === '/api/library') body = { items: [], total: 0, offset: 0, limit: 200, revision: 1 }
    else if (url.pathname === '/api/search') body = { items: [online], total: 1, page: 1, limit: 50 }
    else if (url.pathname === '/api/downloads' && method === 'GET') body = jobs
    else if (url.pathname === '/api/downloads' && method === 'POST') {
      const job = { id: 'job-live', title: online.title, artist: online.artist, status: 'downloading', downloadedBytes: 256 * 1024, totalBytes: 1024 * 1024, createdAt: Date.now(), updatedAt: Date.now() }
      jobs = [job]
      return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify(job) })
    }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })

  await page.goto('/#search')
  await page.getByLabel('在线搜索').fill('正在回家')
  await page.locator('.search-box').getByRole('button', { name: '搜索', exact: true }).click()
  await page.getByRole('button', { name: '下载 正在回家 到 NAS' }).click()
  await expect(page.getByRole('status')).toContainText('已加入 NAS 下载队列')
  await page.getByRole('button', { name: '查看下载' }).click()

  await expect(page).toHaveURL(/#downloads$/)
  await expect(page.getByRole('heading', { name: '下载到 NAS' })).toBeVisible()
  await expect(page.getByText('25% · 256 KB / 1.0 MB')).toBeVisible()
  await expect(page.getByRole('progressbar', { name: /正在回家：25%/ })).toHaveAttribute('max', String(1024 * 1024))
  await expect(page.locator('.mobile-nav .nav-button')).toHaveCount(4)
  await expect(page.locator('.mobile-nav')).not.toContainText('下载任务')

  await page.setViewportSize({ width: 320, height: 720 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  await page.setViewportSize({ width: 1366, height: 768 })
  await expect(page.locator('.sidebar').getByRole('button', { name: '下载任务，1 个进行中' })).toBeVisible()
})

test('failed download can be retried from the queue', async ({ page }) => {
  let job = { id: 'job-failed', title: '暂时失败的歌', artist: '在线歌手', status: 'failed', error: 'download_http_503', downloadedBytes: 64000, totalBytes: 1000000, createdAt: 10, updatedAt: 20 }
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const method = route.request().method()
    let body: unknown = []
    if (url.pathname === '/api/library') body = { items: [], total: 0, offset: 0, limit: 200, revision: 1 }
    else if (url.pathname === '/api/downloads' && method === 'GET') body = [job]
    else if (url.pathname === `/api/downloads/${job.id}/retry` && method === 'POST') {
      job = { ...job, status: 'queued', error: undefined, downloadedBytes: 0, totalBytes: undefined, updatedAt: 30 }
      body = job
    }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })

  await page.goto('/#downloads')
  await expect(page.getByText('音源返回异常状态 503')).toBeVisible()
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.getByText('等待中', { exact: true })).toBeVisible()
  await expect(page.getByText('等待可用下载线程')).toBeVisible()
})

test('mobile home keeps the original swipeable three-column speed dial with random play last', async ({ page }) => {
  await page.setViewportSize({ width: 412, height: 915 })
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const tracks = Array.from({ length: 8 }, (_, index) => ({ id: `home-${index}`, title: `首页歌曲 ${index + 1}`, artist: 'SHiNe', album: '每日发现', durationMs: 180000 }))
    const body = url.pathname === '/api/library' ? { items: tracks, total: tracks.length, offset: 0, limit: 200, revision: 1 } : []
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/#home')
  const firstPage = page.locator('.speed-dial-page').first().locator(':scope > button')
  await expect(firstPage).toHaveCount(3)
  await expect(firstPage.nth(2)).toContainText('随机播放')
  await expect(page.locator('.speed-dial-pages button')).toHaveCount(3)
  const viewport = page.locator('.speed-dial-viewport')
  await expect(viewport).toHaveCSS('scroll-snap-type', 'x mandatory')
  await viewport.hover()
  await page.mouse.wheel(420, 0)
  await expect(page.getByRole('button', { name: '第 2 页' })).toHaveAttribute('aria-current', 'page')
  await expect.poll(() => viewport.evaluate((element) => element.scrollLeft)).toBeGreaterThan(250)
  const secondPage = page.locator('.speed-dial-page').nth(1)
  await expect(secondPage.getByText('播放列表', { exact: true })).toBeVisible()
  await expect(secondPage.getByText('同步房间', { exact: true })).toBeVisible()
  await expect(secondPage.getByText('继续聆听', { exact: true })).toBeVisible()
})

test('mobile player keeps gesture-driven motion and animated dismissal', async ({ page }) => {
  await page.setViewportSize({ width: 412, height: 915 })
  const track = { id: 'motion-track', title: '动效测试歌曲', artist: 'SHiNe', album: '动效专辑', durationMs: 180000 }
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/library') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [track], total: 1, offset: 0, limit: 200, revision: 1 }) })
    if (url.pathname.startsWith('/api/media/')) return route.fulfill({ status: 206, contentType: 'audio/mpeg', body: '' })
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/#local')
  await page.getByRole('button', { name: '播放 动效测试歌曲' }).click()
  await page.locator('.player-track').click()
  const player = page.locator('.now-playing')
  await expect(player).toBeVisible()
  await expect(player).toHaveClass(/presentation-opening/)
  const stage = player.locator('.now-stage')
  await stage.dispatchEvent('pointerdown', { pointerType: 'touch', pointerId: 19, button: 0, clientX: 240, clientY: 350 })
  await stage.dispatchEvent('pointermove', { pointerType: 'touch', pointerId: 19, buttons: 1, clientX: 150, clientY: 350 })
  await expect(stage).toHaveClass(/dragging/)
  await expect.poll(() => stage.evaluate((element) => getComputedStyle(element).transform)).not.toBe('none')
  await stage.dispatchEvent('pointerup', { pointerType: 'touch', pointerId: 19, button: 0, clientX: 110, clientY: 350 })
  await expect(stage).toHaveClass(/next/)
  await player.locator('.close-now-playing').click()
  await expect(player).toHaveClass(/presentation-closing/)
  await expect(player).toHaveCount(0, { timeout: 1000 })
})

test('original top app bar exposes history, listening statistics and grouped settings', async ({ page }) => {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const body = url.pathname === '/api/library' ? { items: [], total: 0, offset: 0, limit: 200, revision: 1 } : []
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/#home')
  await page.getByRole('button', { name: '打开播放历史' }).click()
  await expect(page.getByRole('heading', { name: '历史记录' })).toBeVisible()
  await page.getByRole('button', { name: '打开收听统计' }).click()
  await expect(page.getByRole('heading', { name: '收听统计' })).toBeVisible()
  await page.getByRole('button', { name: '打开设置' }).click()
  await expect(page.getByRole('heading', { name: '界面' })).toBeVisible()
  await expect(page.getByRole('button', { name: /关于 SHiNe MUSIC/ })).toBeVisible()
})

test('search restores two paired source capsules and persistent history chips', async ({ page }) => {
  await page.setViewportSize({ width: 412, height: 915 })
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    let body: unknown = []
    if (url.pathname === '/api/library') body = { items: [], total: 0, offset: 0, limit: 200, revision: 1 }
    if (url.pathname === '/api/search') body = { items: [], total: 0, page: 1, limit: 50 }
    if (url.pathname === '/api/search/playlists') body = { items: [{ id: 'online-list', name: '夜跑精选', author: 'SHiNe', playCount: 12000, source: 'wy' }], total: 1, page: 1, limit: 20, allPages: 1, source: 'wy' }
    if (url.pathname === '/api/search/playlists/detail') body = { id: 'online-list', name: '夜跑精选', author: 'SHiNe', description: '稳定风格的夜跑歌单', tracks: [{ id: 'online-song', title: '夜色节拍', artist: '在线歌手', album: '夜跑精选', durationMs: 180000, source: 'wy' }], total: 1, page: 1, limit: 100, allPages: 1, source: 'wy' }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/#search')
  await expect(page.getByRole('tab', { name: '国内歌曲' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '国内歌单' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '曲库' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '高级听感' })).toBeVisible()
  await page.getByLabel('在线搜索').fill('Cruel Summer')
  await page.locator('.search-box').getByRole('button', { name: '搜索', exact: true }).click()
  await page.reload()
  await page.getByLabel('在线搜索').fill('')
  await expect(page.getByRole('button', { name: 'Cruel Summer' })).toBeVisible()
  await page.getByRole('tab', { name: '国内歌单' }).click()
  await expect(page.getByText('搜索国内歌单')).toBeVisible()
  await page.getByLabel('在线搜索').fill('夜跑')
  await page.locator('.search-box').getByRole('button', { name: '搜索', exact: true }).click()
  const playlistOpener = page.getByRole('button', { name: '打开歌单 夜跑精选' })
  await playlistOpener.click()
  const confirmation = page.getByRole('dialog', { name: '是否打开这个网易云歌单？' })
  await expect(confirmation).toBeVisible()
  await expect(confirmation.getByRole('button', { name: '取消' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(confirmation).toBeHidden()
  await expect(playlistOpener).toBeFocused()
  await playlistOpener.click()
  await confirmation.getByRole('button', { name: '打开' }).click()
  await expect(page.getByRole('heading', { name: '夜跑精选' })).toBeVisible()
  await expect(page.getByRole('button', { name: '返回在线歌单' })).toBeFocused()
  await expect(page.getByText('夜色节拍')).toBeVisible()
})

test('sync status describes automatic clock calibration instead of listening error', async ({ page }) => {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const body = url.pathname === '/api/library' ? { items: [], total: 0, offset: 0, limit: 200, revision: 1 } : []
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/#rooms')
  await expect(page.getByText('自动时钟校准')).toBeVisible()
  await expect(page.getByText(/NAS 时钟偏移/)).toBeVisible()
  await expect(page.getByText(/同步误差/)).toHaveCount(0)
})

test('player exposes lyrics and an editable queue on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 412, height: 915 })
  const tracks = Array.from({ length: 3 }, (_, index) => ({ id: `queue-${index}`, title: `队列歌曲 ${index + 1}`, artist: '家庭歌手', album: '家庭精选', durationMs: 180000 }))
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/library') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: tracks, total: tracks.length, offset: 0, limit: 200, revision: 1 }) })
    if (url.pathname.startsWith('/api/media/')) return route.fulfill({ status: 206, contentType: 'audio/mpeg', body: '' })
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/#local')
  await page.getByRole('button', { name: '播放 队列歌曲 1' }).first().click()
  await page.locator('.player-track').click()
  await page.getByRole('tab', { name: '队列' }).click()
  await expect(page.getByRole('region', { name: '可编辑播放队列' })).toBeVisible()
  await page.getByRole('button', { name: '下移 队列歌曲 1' }).click()
  await expect(page.locator('.now-playing .queue-editor-item').first()).toContainText('队列歌曲 2')
  await page.getByRole('button', { name: '从队列移除 队列歌曲 3' }).click()
  await expect(page.locator('.now-playing .queue-editor-item')).toHaveCount(2)
  await page.getByRole('tab', { name: '歌词' }).click()
  await expect(page.getByText('这首歌还没有歌词')).toBeVisible()
  await page.getByRole('button', { name: '添加歌词' }).click()
  await page.getByLabel('歌词文本').fill('第一行\n第二行')
  await page.getByRole('button', { name: '保存歌词' }).click()
  await expect(page.getByText('第一行')).toBeVisible()
})

test('shared playlist detail has artwork, duration, play controls and persistent reordering', async ({ page }) => {
  const tracks = [
    { id: 'playlist-a', title: '第一首', artist: '歌手 A', album: '专辑', durationMs: 120000 },
    { id: 'playlist-b', title: '第二首', artist: '歌手 B', album: '专辑', durationMs: 180000 },
  ]
  let detail = { id: 'shared-1', name: '家庭精选', version: 1, tracks, updatedAt: 1 }
  let updateBody: { trackIds: string[]; expectedVersion: number } | null = null
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/library') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: tracks, total: tracks.length, offset: 0, limit: 200, revision: 1 }) })
    if (url.pathname === '/api/playlists' && route.request().method() === 'GET') return route.fulfill({ contentType: 'application/json', body: JSON.stringify([{ id: 'shared-1', name: '家庭精选', version: detail.version, trackCount: 2, updatedAt: 1 }]) })
    if (url.pathname === '/api/playlists/shared-1' && route.request().method() === 'GET') return route.fulfill({ contentType: 'application/json', body: JSON.stringify(detail) })
    if (url.pathname === '/api/playlists/shared-1' && route.request().method() === 'PUT') {
      updateBody = route.request().postDataJSON() as { trackIds: string[]; expectedVersion: number }
      detail = { ...detail, version: 2, tracks: updateBody.trackIds.map((id) => tracks.find((track) => track.id === id)!) }
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify(detail) })
    }
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/#library')
  await page.getByRole('button', { name: /家庭精选/ }).click()
  await expect(page.locator('.playlist-detail-hero')).toContainText('家庭精选')
  await expect(page.locator('.playlist-detail-hero')).toContainText('5:00')
  await expect(page.getByRole('button', { name: '播放歌单 家庭精选' })).toBeEnabled()
  await page.getByRole('button', { name: '下移 第一首' }).click()
  await expect.poll(() => updateBody?.trackIds.join(',')).toBe('playlist-b,playlist-a')
  expect(updateBody?.expectedVersion).toBe(1)
  await expect(page.locator('.track-row').first()).toContainText('第二首')
})

test('advanced filter exposes the original Camelot and interactive radar workflow', async ({ page }) => {
  const match = {
    id: 'advanced-match', title: '邻位延续', artist: 'SHiNe', album: '听感测试', durationMs: 180000,
    analysis: { status: 'completed', progress: 1, bpm: 118, keyName: 'F', camelot: '7B', valence: .54, energy: .82, danceability: .61, acousticness: .2, instrumentalness: .08, liveness: .15, speechiness: .1 },
  }
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/library') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [], total: 0, offset: 0, limit: 200, revision: 1 }) })
    if (url.pathname === '/api/libraries') return route.fulfill({ contentType: 'application/json', body: '[]' })
    if (url.pathname === '/api/analysis') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ available: true, implementation: 'vibenet', total: 2, pending: 0, queued: 0, running: 0, completed: 2, failed: 0 }) })
    if (url.pathname === '/api/library/advanced-search') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [{ track: match, similarityPercent: 94, bpmDelta: -2, camelotDelta: -1, camelotModeChanged: false }], totalCandidates: 1 }) })
    await route.fulfill({ contentType: 'application/json', body: '[]' })
  })
  await page.goto('/#search')
  await page.getByRole('tab', { name: '高级听感' }).click()

  const bpm = page.getByRole('spinbutton', { name: /目标 BPM/ })
  await expect(bpm).toHaveAttribute('max', '220')
  await bpm.fill('221')
  await expect(bpm).toHaveValue('220')
  await page.getByRole('button', { name: /^8A，/ }).click()
  await page.getByRole('button', { name: '增加 Camelot 邻位容差' }).click()
  await expect(page.getByText('邻位 ±1')).toBeVisible()
  await page.getByRole('tab', { name: '雷达' }).click()
  const energy = page.getByRole('slider', { name: /能量目标/ })
  await energy.focus()
  await page.keyboard.press('ArrowUp')
  await expect(energy).toHaveAttribute('aria-valuetext', /已启用/)

  const advancedRequest = page.waitForRequest((request) => request.url().endsWith('/api/library/advanced-search'))
  await page.getByRole('button', { name: '执行筛选' }).click()
  const request = await advancedRequest
  expect(request.postDataJSON()).toMatchObject({ bpm: 220, keyName: '8A', keyTolerance: 1, energy: .51 })
  await expect(page.getByText('94% 相似')).toBeVisible()
  await expect(page.getByText('118 BPM · Δ-2')).toBeVisible()
  await expect(page.getByText('F · 7B · 邻位 -1')).toBeVisible()
  await expect(page.locator('.advanced-comparison-radar')).toHaveCount(1)
})
