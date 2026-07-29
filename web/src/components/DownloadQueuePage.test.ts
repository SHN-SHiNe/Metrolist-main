import { describe, expect, it } from 'vitest'
import type { DownloadJob } from '../types'
import { downloadCounts, downloadGroup, downloadProgressLabel, formatBytes, sortDownloadJobs } from './DownloadQueuePage'

const job = (id: string, status: string, createdAt: number, downloadedBytes = 0, totalBytes?: number): DownloadJob => ({
  id, status, createdAt, updatedAt: createdAt, title: id, artist: 'Artist', downloadedBytes, totalBytes,
})

describe('download queue presentation', () => {
  it('keeps active work ahead of failed and completed history', () => {
    const result = sortDownloadJobs([
      job('done', 'completed', 9), job('failed', 'failed', 8), job('queued', 'queued', 7), job('loading', 'downloading', 10),
    ])
    expect(result.map((item) => item.id)).toEqual(['loading', 'queued', 'failed', 'done'])
    expect(downloadCounts(result)).toEqual({ active: 2, failed: 1, completed: 1 })
    expect(downloadGroup(result[0])).toBe('active')
  })

  it('shows real byte progress without inventing a percentage when total is unknown', () => {
    expect(downloadProgressLabel(job('known', 'downloading', 1, 5 * 1024 * 1024, 10 * 1024 * 1024))).toBe('50% · 5.0 MB / 10 MB')
    expect(downloadProgressLabel(job('unknown', 'downloading', 1, 1536))).toBe('已下载 1.5 KB · 正在获取总大小')
    expect(formatBytes(0)).toBe('0 B')
  })
})
