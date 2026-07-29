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
