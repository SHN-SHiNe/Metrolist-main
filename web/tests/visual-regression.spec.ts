import { expect, test, type Page } from '@playwright/test'

const completedAnalysis = {
  status: 'completed', progress: 1, bpm: 124, keyName: 'Am', camelot: '8A',
  valence: .64, energy: .72, danceability: .68, acousticness: .22,
  instrumentalness: .38, liveness: .18, speechiness: .08,
}

const tracks = Array.from({ length: 12 }, (_, index) => ({
  id: `visual-${index + 1}`,
  title: ['Cruel Summer', 'Last Sunset', "L'Absente", 'Celestial Flow', 'Ethereal Rhapsody', 'Afterglow'][index % 6] + (index > 5 ? ` ${index + 1}` : ''),
  artist: index % 2 ? "Simon O'Shine" : 'Taylor Swift',
  album: index % 2 ? '意境大师' : '家庭精选',
  durationMs: 178000 + index * 7000,
  analysis: index === 11 ? { status: 'running', progress: .46, message: '提取七维听感' } : completedAnalysis,
}))

const playlist = { id: 'visual-playlist', name: '夜间驰放', version: 1, tracks: tracks.slice(0, 6), updatedAt: 1 }
const similar = { seed: tracks[0], items: tracks.slice(1, 7).map((track, index) => ({ track, similarityPercent: 96 - index * 3, bpmDelta: index - 2, camelotDelta: index % 2 })) }
const onlinePlaylists = {
  items: [
    { id: 'cloud-night', name: '云端夜间驰放', author: 'SHiNe Select', playCount: 182400, source: 'netease' },
    { id: 'cloud-trance', name: '意境 Trance 长途', author: '浩南的收藏', playCount: 92800, source: 'netease' },
    { id: 'cloud-focus', name: '专注但不打扰', author: '音乐生活家', playCount: 38600, source: 'netease' },
  ],
  total: 3, page: 1, limit: 20, allPages: 1, source: 'netease',
}
const onlinePlaylistDetail = {
  id: onlinePlaylists.items[0].id,
  name: onlinePlaylists.items[0].name,
  author: onlinePlaylists.items[0].author,
  description: '保持相近风格与情绪，让一整晚不必频繁切歌。',
  tracks: tracks.slice(0, 6),
  total: 6, page: 1, limit: 100, allPages: 1, source: 'netease',
}

test.describe('Android gold-standard mobile states', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'mobile', 'Android visual baselines only run in the mobile project')
    await page.emulateMedia({ reducedMotion: 'reduce', colorScheme: 'dark' })
    await page.addInitScript(() => {
      localStorage.setItem('shine-theme', 'dark')
      localStorage.setItem('shine-search-history', JSON.stringify(['Cruel Summer', 'Simon O\'Shine', 'beautiful love']))
    })
    await mockProductState(page)
  })

  test('01 domestic playlist discovery', async ({ page }) => {
    await openOnlinePlaylists(page)
    await expect(page).toHaveScreenshot('01-online-playlist-results.png', screenshotOptions)
  })

  test('02 domestic playlist detail', async ({ page }) => {
    await openOnlinePlaylists(page)
    await page.getByRole('button', { name: `打开歌单 ${onlinePlaylists.items[0].name}` }).click()
    await expect(page.getByRole('heading', { name: onlinePlaylistDetail.name })).toBeVisible()
    await page.locator('.online-playlist-browser.detail').evaluate((element) => element.scrollTo(0, 0))
    await expect(page).toHaveScreenshot('02-online-playlist-detail.png', screenshotOptions)
  })

  test('03 service and about settings', async ({ page }) => {
    await page.goto('/#settings')
    await page.getByRole('heading', { name: 'HTTP 模式' }).scrollIntoViewIfNeeded()
    await expect(page).toHaveScreenshot('03-settings-service-info.png', screenshotOptions)
  })

  test('04 search sources and history', async ({ page }) => {
    await page.goto('/#search')
    await expect(page).toHaveScreenshot('04-search-sources-history.png', screenshotOptions)
  })

  test('05 full cover player', async ({ page }) => {
    await openPlayer(page, tracks[0].title)
    await expect(page).toHaveScreenshot('05-player-cover.png', screenshotOptions)
  })

  test('06 lyrics player', async ({ page }) => {
    await openPlayer(page, tracks[0].title)
    await page.getByRole('tab', { name: '歌词' }).click()
    await expect(page).toHaveScreenshot('06-player-lyrics.png', screenshotOptions)
  })

  test('07 editable queue player', async ({ page }) => {
    await openPlayer(page, tracks[0].title)
    await page.getByRole('tab', { name: '队列' }).click()
    await expect(page).toHaveScreenshot('07-player-queue.png', screenshotOptions)
  })

  test('08 shared playlist detail', async ({ page }) => {
    await page.goto('/#library')
    await page.getByRole('button', { name: /夜间驰放/ }).click()
    await expect(page).toHaveScreenshot('08-playlist-detail.png', screenshotOptions)
  })

  test('09 media library', async ({ page }) => {
    await page.goto('/#library')
    await expect(page).toHaveScreenshot('09-media-library.png', screenshotOptions)
  })

  test('10 home listening feed', async ({ page }) => {
    await page.goto('/#home')
    await page.getByRole('heading', { name: 'Quick Picks' }).scrollIntoViewIfNeeded()
    await expect(page).toHaveScreenshot('10-home-listening-feed.png', screenshotOptions)
  })

  test('11 advanced BPM filter', async ({ page }) => {
    await openAdvanced(page)
    await page.getByLabel('目标 BPM，范围 40 到 220').fill('124')
    await page.getByText('速度', { exact: true }).scrollIntoViewIfNeeded()
    await expect(page).toHaveScreenshot('11-advanced-bpm.png', screenshotOptions)
  })

  test('12 advanced radar input', async ({ page }) => {
    await openAdvanced(page)
    await page.getByRole('tab', { name: '雷达' }).click()
    await page.locator('.dimension-toggle-strip').scrollIntoViewIfNeeded()
    await expect(page).toHaveScreenshot('12-advanced-radar.png', screenshotOptions)
  })

  test('13 advanced ranked results', async ({ page }) => {
    await openAdvanced(page)
    await page.getByLabel('目标 BPM，范围 40 到 220').fill('124')
    await page.getByRole('button', { name: '执行筛选' }).click()
    await page.getByText(/% 相似/).first().scrollIntoViewIfNeeded()
    await expect(page).toHaveScreenshot('13-advanced-results.png', screenshotOptions)
  })

  test('14 similar continuation page', async ({ page }) => {
    await openPlayer(page, tracks[0].title)
    await page.getByRole('tab', { name: '相似音乐' }).click()
    await expect(page).toHaveScreenshot('14-player-similar.png', screenshotOptions)
  })

  test('15 analysis radar player', async ({ page }) => {
    await openPlayer(page, tracks[0].title)
    await page.getByRole('tab', { name: '音乐画像' }).click()
    await expect(page).toHaveScreenshot('15-player-analysis-radar.png', screenshotOptions)
  })

  test('16 analysis progress player', async ({ page }) => {
    await openPlayer(page, tracks[11].title)
    await page.getByRole('tab', { name: '音乐画像' }).click()
    await expect(page).toHaveScreenshot('16-player-analysis-progress.png', screenshotOptions)
  })
})

const screenshotOptions = { animations: 'disabled' as const, caret: 'hide' as const, maxDiffPixelRatio: 0.08 }

async function openPlayer(page: Page, title: string) {
  await page.goto('/#local')
  await page.getByRole('button', { name: `播放 ${title}`, exact: true }).first().click()
  await page.locator('.player-track').click()
  await expect(page.getByRole('dialog', { name: '正在播放' })).toBeVisible()
}

async function openAdvanced(page: Page) {
  await page.goto('/#search')
  await page.getByRole('tab', { name: '高级听感' }).click()
  await expect(page.getByRole('heading', { name: /个条件/ })).toBeVisible()
}

async function openOnlinePlaylists(page: Page) {
  await page.goto('/#search')
  await page.getByRole('tab', { name: '国内歌单' }).click()
  await page.getByLabel('在线搜索').fill('夜间驰放')
  await page.getByRole('search').getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('heading', { name: /夜间驰放.*在线歌单/ })).toBeVisible()
  await expect(page.getByRole('button', { name: `打开歌单 ${onlinePlaylists.items[0].name}` })).toBeVisible()
}

async function mockProductState(page: Page) {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const method = route.request().method()
    let body: unknown = []
    if (url.pathname === '/api/library') body = { items: tracks, total: tracks.length, offset: 0, limit: 200, revision: 1 }
    else if (url.pathname === '/api/favorites') body = tracks.slice(0, 5)
    else if (url.pathname === '/api/history') body = tracks.slice(0, 6).map((track, index) => ({ id: index + 1, track, playedAt: 1000 - index }))
    else if (url.pathname === '/api/playlists' && method === 'GET') body = [{ id: playlist.id, name: playlist.name, version: playlist.version, trackCount: playlist.tracks.length, updatedAt: playlist.updatedAt }]
    else if (url.pathname === `/api/playlists/${playlist.id}`) body = playlist
    else if (url.pathname === '/api/rooms') body = [{ id: 'visual-room', name: '全屋同步', memberCount: 3, version: 1, updatedAt: 1 }]
    else if (url.pathname === '/api/libraries') body = [{ id: 'nas', name: 'NAS 音乐', path: '/music', deviceType: 'local', readOnly: false, enabled: true, downloadTarget: true, status: 'online', trackCount: tracks.length, createdAt: 1, updatedAt: 1 }]
    else if (url.pathname === '/api/settings/sources') body = []
    else if (url.pathname === '/api/downloads') body = []
    else if (url.pathname === '/api/search/playlists') body = onlinePlaylists
    else if (url.pathname === '/api/search/playlists/detail') body = onlinePlaylistDetail
    else if (url.pathname === '/api/search') body = { items: tracks.slice(0, 6), total: 6, page: 1, limit: 50 }
    else if (url.pathname === '/api/analysis' && method === 'GET') body = { available: true, implementation: 'vibenet', total: 12, pending: 1, queued: 0, running: 1, completed: 11, failed: 0 }
    else if (url.pathname === '/api/library/advanced-search') body = { items: similar.items, totalCandidates: similar.items.length }
    else if (url.pathname.endsWith('/similar')) body = similar
    else if (url.pathname === '/api/tracks') body = tracks.filter((track) => (url.searchParams.get('ids') ?? '').includes(track.id))
    else if (url.pathname.startsWith('/api/media/')) return route.fulfill({ status: 206, contentType: 'audio/mpeg', body: '' })
    else if (method !== 'GET') body = {}
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
  })
}
