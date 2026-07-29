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
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    let body: unknown = []
    if (url.pathname === '/api/library') body = { items: [], total: 0, offset: 0, limit: 200, revision: 1 }
    else if (url.pathname === '/api/search') body = { items: [online], total: 1, page: 1, limit: 50 }
    else if (url.pathname === '/api/libraries') body = []
    else if (url.pathname === '/api/analysis' && route.request().method() === 'POST') { analysisPosts++; body = { queued: 0, draining: false } }
    else if (url.pathname.startsWith('/api/media/')) return route.fulfill({ status: 206, contentType: 'audio/mpeg', body: '' })
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/#search')
  await page.getByLabel('在线搜索').fill('云端试听')
  await page.locator('.search-box').getByRole('button', { name: '搜索', exact: true }).click()
  await page.locator('.track-identity').filter({ hasText: '云端试听' }).click()
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
