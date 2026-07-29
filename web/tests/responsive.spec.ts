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
  await page.locator('.nav-button:visible').filter({ hasText: 'NAS 曲库' }).click()

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

  await page.goto('/#library')
  await page.getByLabel('按音频库筛选').selectOption('usb')
  await expect(page.getByText('移动歌曲')).toBeVisible()
  await expect(page.getByText('本地歌曲')).not.toBeVisible()
})
