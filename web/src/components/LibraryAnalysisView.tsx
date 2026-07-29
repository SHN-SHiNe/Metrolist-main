import { useMemo, useState } from 'react'
import type { AnalysisStatus, AnalysisSummary, Track } from '../types'
import { Icon } from './Icon'
import { analysisValues, RadarChart, radarDimensions } from './RadarChart'
import { AlbumArt } from './TrackList'

export type AnalysisViewTab = 'pending' | 'completed'

export function groupTracksByAnalysis(tracks: Track[]) {
  return tracks.reduce<{ pending: Track[]; completed: Track[] }>((groups, track) => {
    groups[track.analysis?.status === 'completed' ? 'completed' : 'pending'].push(track)
    return groups
  }, { pending: [], completed: [] })
}

export function LibraryAnalysisView({ tracks, summary, onPlay, onAnalyze, onAnalyzeAll, busy = false }: { tracks: Track[]; summary?: AnalysisSummary | null; onPlay: (track: Track, queue: Track[]) => void; onAnalyze: (track: Track) => void; onAnalyzeAll: () => void; busy?: boolean }) {
  const groups = useMemo(() => groupTracksByAnalysis(tracks), [tracks])
  const [tab, setTab] = useState<AnalysisViewTab>(() => groups.pending.length ? 'pending' : 'completed')
  const [query, setQuery] = useState('')
  const shown = groups[tab].filter((track) => `${track.title}\n${track.artist}\n${track.album}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
  const total = summary?.total ?? tracks.length
  const completedCount = summary?.completed ?? groups.completed.length
  const pendingCount = summary ? Math.max(0, summary.total - summary.completed) : groups.pending.length
  const percent = total ? Math.round(completedCount / total * 100) : 0

  return <section className="library-analysis-view" aria-label="曲库音乐分析">
    <header className="analysis-view-hero">
      <div><span className="section-kicker"><Icon name="radar" />音乐画像</span><h2>听懂整个曲库</h2><p>节拍、调性与七维听感在 NAS 后台计算；完成后可用于相似续播与高级筛选。</p></div>
      <div className="analysis-view-meter" aria-label={`分析进度 ${percent}%`}><strong>{percent}%</strong><span>{completedCount} / {total} 首已完成</span><progress max={Math.max(1, total)} value={completedCount} /></div>
    </header>

    <div className="analysis-view-toolbar">
      <div className="analysis-view-tabs" role="tablist" aria-label="分析状态"><button role="tab" aria-selected={tab === 'pending'} className={tab === 'pending' ? 'active' : ''} onClick={() => setTab('pending')}>待分析 <span>{pendingCount}</span></button><button role="tab" aria-selected={tab === 'completed'} className={tab === 'completed' ? 'active' : ''} onClick={() => setTab('completed')}>已分析 <span>{completedCount}</span></button></div>
      <label className="analysis-view-search"><Icon name="search" /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="筛选曲名、歌手或专辑" aria-label="筛选分析曲目" /></label>
      {tab === 'pending' && <button className="primary-button" disabled={busy || !pendingCount || summary?.available === false} onClick={onAnalyzeAll}><Icon name="sparkles" />{busy ? '正在加入队列…' : '分析全部待处理'}</button>}
    </div>

    {summary?.available === false && <div className="analysis-unavailable" role="status"><Icon name="radar" /><span><strong>这台设备暂不支持音乐画像</strong><small>{summary.unavailableReason || '仍可正常浏览与播放曲库。'}</small></span></div>}
    {shown.length ? <div className="analysis-track-grid">{shown.map((track) => tab === 'completed'
      ? <CompletedAnalysisCard key={track.id} track={track} queue={shown} onPlay={onPlay} />
      : <PendingAnalysisRow key={track.id} track={track} queue={shown} disabled={busy || summary?.available === false} onPlay={onPlay} onAnalyze={onAnalyze} />)}</div>
      : <div className="analysis-view-empty"><Icon name={tab === 'completed' ? 'radar' : 'sparkles'} /><strong>{query ? '没有匹配的曲目' : tab === 'completed' ? '还没有完成分析的曲目' : '待分析队列已清空'}</strong><span>{query ? '试试更短的关键词。' : tab === 'completed' ? '切到待分析并启动后台分析。' : '相似续播和高级筛选已经可以使用。'}</span></div>}
  </section>
}

function CompletedAnalysisCard({ track, queue, onPlay }: { track: Track; queue: Track[]; onPlay: (track: Track, queue: Track[]) => void }) {
  const values = analysisValues(track.analysis)
  return <article className="analysis-track-card">
    <button className="analysis-card-identity" onClick={() => onPlay(track, queue)} aria-label={`播放 ${track.title}`}><AlbumArt title={track.title} artworkUrl={track.artworkUrl} /><span><strong>{track.title}</strong><small>{track.artist}</small></span><Icon name="play" /></button>
    <div className="analysis-card-profile"><RadarChart analysis={track.analysis} compact /><div className="analysis-card-facts"><span><b>{track.analysis?.bpm ? Math.round(track.analysis.bpm) : '—'}</b><small>BPM</small></span><span><b>{track.analysis?.keyName || '—'}</b><small>调性</small></span><span><b>{track.analysis?.camelot || '—'}</b><small>Camelot</small></span></div></div>
    <div className="analysis-dimensions" aria-label="七维音乐画像">{radarDimensions.map(([, label], index) => <span key={label} title={`${label} ${Math.round(values[index] * 100)}`}><small>{label}</small><i><b style={{ width: `${Math.round(values[index] * 100)}%` }} /></i></span>)}</div>
  </article>
}

function PendingAnalysisRow({ track, queue, disabled, onPlay, onAnalyze }: { track: Track; queue: Track[]; disabled: boolean; onPlay: (track: Track, queue: Track[]) => void; onAnalyze: (track: Track) => void }) {
  const status = track.analysis?.status ?? 'pending'
  const active = status === 'queued' || status === 'running'
  return <article className="analysis-pending-row">
    <button className="analysis-pending-identity" onClick={() => onPlay(track, queue)}><AlbumArt title={track.title} artworkUrl={track.artworkUrl} small /><span><strong>{track.title}</strong><small>{track.artist} · {track.album || 'NAS 音乐'}</small></span></button>
    <span className={`analysis-status ${status}`}><i />{analysisStatusLabel(status, track.analysis?.progress)}</span>
    {active && <progress max="1" value={track.analysis?.progress ?? 0} aria-label={`${track.title} 分析进度`} />}
    <button className="secondary-button" disabled={disabled || active || status === 'unavailable'} onClick={() => onAnalyze(track)}>{status === 'failed' ? '重试' : active ? '分析中' : '分析'}</button>
  </article>
}

function analysisStatusLabel(status: AnalysisStatus, progress = 0) {
  switch (status) {
    case 'queued': return '等待中'
    case 'running': return `分析中 ${Math.round(progress * 100)}%`
    case 'failed': return '上次失败'
    case 'unavailable': return '不可用'
    default: return '待分析'
  }
}
