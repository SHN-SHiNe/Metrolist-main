import { expect, test } from '@playwright/test'

test('app shell exposes navigation and playback controls', async ({ page }) => {
  await page.route('**/api/**', async (route) => {
    const url = route.request().url()
    const body = url.includes('/library') ? { items: [], total: 0, offset: 0, limit: 50 } : []
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '首页' })).toBeVisible()
  await expect(page.locator('nav:visible').first()).toBeVisible()
  await expect(page.getByText('选择一首歌开始播放')).toBeVisible()
})
