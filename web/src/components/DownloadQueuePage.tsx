import { useEffect, useMemo, useState } from 'react'
import type { DownloadJob } from '../types'
import { EmptyState } from './TrackList'
import { Icon } from './Icon'

type DownloadFilter = 'all' | 'active' | 'failed' | 'completed'

export function DownloadQueuePage({ jobs, onRefresh, onRetry }: {
  jobs: DownloadJob[]
  onRefresh: () => Promise<void>
  onRetry: (id: string) => Promise<void>
}) {
  const [filter, setFilter] = useState<DownloadFilter>('all')
  const [refreshing, setRefreshing] = useState(false)
  const [retryingId, setRetryingId] = useState<string | null>(null)
  const counts = useMemo(() => downloadCounts(jobs), [jobs])
  const visible = useMemo(() => sortDownloadJobs(jobs).filter((job) => filter === 'all' || downloadGroup(job) === filter), [filter, jobs])

  useEffect(() => { void onRefresh() }, [onRefresh])

  const refresh = async () => {
    if (refreshing) return
    setRefreshing(true)
    try { await onRefresh() } finally { setRefreshing(false) }
  }

  const retry = async (id: string) => {
    if (retryingId) return
    setRetryingId(id)
    try { await onRetry(id) } finally { setRetryingId(null) }
  }

  return <div className="downloads-page">
    <section className="downloads-intro">
      <span className="downloads-intro-icon" aria-hidden="true"><Icon name="download" /></span>
      <div>
        <h2>下载到 NAS</h2>
        <p>在线歌曲会先进入队列，完成后自动写入下载目标并加入本地曲库。页面会在任务进行时自动刷新。</p>
        <p className="downloads-live-summary" aria-live="polite">{downloadSummary(counts)}</p>
      </div>
      <button className="secondary-button" onClick={() => void refresh()} disabled={refreshing}><Icon name="refresh" />{refreshing ? '刷新中…' : '刷新'}</button>
    </section>

    <div className="download-filters" role="tablist" aria-label="下载任务筛选">
      {([
        ['all', '全部', jobs.length],
        ['active', '进行中', counts.active],
        ['failed', '失败', counts.failed],
        ['completed', '已完成', counts.completed],
      ] as const).map(([value, label, count]) => <button key={value} role="tab" aria-selected={filter === value} className={filter === value ? 'active' : ''} onClick={() => setFilter(value)}>{label}<span>{count}</span></button>)}
    </div>

    {visible.length ? <section className="download-job-list" aria-label="下载任务列表">
      {visible.map((job) => <DownloadJobRow key={job.id} job={job} retrying={retryingId === job.id} onRetry={retry} />)}
    </section> : <EmptyState title={filter === 'all' ? '暂无下载任务' : '这个分类还没有任务'} body={filter === 'all' ? '从国内歌曲或在线歌单点击下载后，真实进度会显示在这里。' : '切换到其他分类查看，或继续从在线搜索添加歌曲。'} />}
  </div>
}

function DownloadJobRow({ job, retrying, onRetry }: { job: DownloadJob; retrying: boolean; onRetry: (id: string) => Promise<void> }) {
  const knownTotal = typeof job.totalBytes === 'number' && job.totalBytes > 0
  const downloaded = Math.max(0, job.downloadedBytes ?? 0)
  const total = knownTotal ? Math.max(downloaded, job.totalBytes!) : null
  const percent = total ? Math.min(100, Math.round((downloaded / total) * 100)) : null
  const active = downloadGroup(job) === 'active'
  const progressLabel = downloadProgressLabel(job, downloaded, total, percent)

  return <article className={`download-job ${job.status}`}>
    <span className="download-job-icon" aria-hidden="true"><Icon name={job.status === 'failed' ? 'refresh' : 'download'} /></span>
    <div className="download-job-body">
      <header><span><strong>{job.title}</strong><small>{job.artist || '未知艺术家'}</small></span><StatusText status={job.status} /></header>
      {(active || job.status === 'completed') && <progress max={total ?? (job.status === 'completed' ? 1 : undefined)} value={total ? downloaded : job.status === 'completed' ? 1 : undefined} aria-label={`${job.title}：${progressLabel}`} />}
      <div className="download-job-detail"><span>{progressLabel}</span><time dateTime={new Date(job.updatedAt).toISOString()}>{formatDownloadTime(job.updatedAt)}</time></div>
      {job.status === 'failed' && job.error && <p className="download-error">{downloadErrorLabel(job.error)}</p>}
    </div>
    {job.status === 'failed' && <button className="secondary-button" disabled={retrying} onClick={() => void onRetry(job.id)}>{retrying ? '重试中…' : '重试'}</button>}
  </article>
}

function StatusText({ status }: { status: string }) {
  const labels: Record<string, string> = { queued: '等待中', downloading: '下载中', completed: '已完成', failed: '失败' }
  return <span className={`download-status ${status}`}><i />{labels[status] ?? status}</span>
}

export function downloadGroup(job: DownloadJob): Exclude<DownloadFilter, 'all'> {
  if (job.status === 'queued' || job.status === 'downloading') return 'active'
  return job.status === 'failed' ? 'failed' : 'completed'
}

export function sortDownloadJobs(jobs: DownloadJob[]) {
  const rank = { active: 0, failed: 1, completed: 2 } as const
  return [...jobs].sort((left, right) => rank[downloadGroup(left)] - rank[downloadGroup(right)] || right.createdAt - left.createdAt)
}

export function downloadCounts(jobs: DownloadJob[]) {
  return jobs.reduce((counts, job) => {
    counts[downloadGroup(job)] += 1
    return counts
  }, { active: 0, failed: 0, completed: 0 })
}

function downloadSummary(counts: ReturnType<typeof downloadCounts>) {
  if (counts.active) return `${counts.active} 个任务进行中${counts.failed ? `，${counts.failed} 个需要处理` : ''}`
  if (counts.failed) return `没有进行中的任务，${counts.failed} 个任务可以重试`
  if (counts.completed) return `当前队列已完成，共保留 ${counts.completed} 条下载记录`
  return '当前队列为空'
}

export function downloadProgressLabel(job: DownloadJob, downloaded = Math.max(0, job.downloadedBytes ?? 0), total = job.totalBytes && job.totalBytes > 0 ? Math.max(downloaded, job.totalBytes) : null, percent = total ? Math.min(100, Math.round((downloaded / total) * 100)) : null) {
  if (job.status === 'queued') return '等待可用下载线程'
  if (job.status === 'downloading') return total && percent !== null ? `${percent}% · ${formatBytes(downloaded)} / ${formatBytes(total)}` : downloaded ? `已下载 ${formatBytes(downloaded)} · 正在获取总大小` : '正在连接音源…'
  if (job.status === 'completed') return `已写入 NAS${downloaded ? ` · ${formatBytes(downloaded)}` : ''}`
  return '下载未完成'
}

export function formatBytes(value: number) {
  if (!Number.isFinite(value) || value <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const unit = Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(1024)))
  const amount = value / (1024 ** unit)
  return `${amount >= 10 || unit === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[unit]}`
}

function formatDownloadTime(value: number) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(value)
}

function downloadErrorLabel(error: string) {
  if (error === 'server_restarted') return 'NAS 服务重启中断了任务，可以重新开始下载。'
  if (error === 'download_library_required') return '还没有设置可写的下载目标，请先到设置中选择音频库。'
  if (error === 'download_library_offline') return '下载目标当前离线，连接设备后即可重试。'
  if (error.startsWith('download_http_')) return `音源返回异常状态 ${error.slice('download_http_'.length)}，可以稍后重试。`
  if (error === 'unable_to_resolve_track') return '音源链接已失效，请重新搜索这首歌。'
  return `失败原因：${error}`
}
