import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import type { SimilarTracksResponse, Track } from '../types'
import type { PlayerController } from '../usePlayer'
import { Icon } from '../components/Icon'
import { RadarChart } from '../components/RadarChart'
import { AlbumArt, formatTime } from '../components/TrackList'

type PlayerView = 'cover' | 'radar' | 'similar'

export function NowPlaying({ player, favorite, similar, similarLoading, onFavorite, onToggle, onPrevious, onNext, onSeek, onClose, onLoadSimilar, onAnalyze, onPlaySimilar }: { player: PlayerController; favorite: boolean; similar: SimilarTracksResponse | null; similarLoading: boolean; onFavorite: () => void; onToggle: () => void; onPrevious: () => void; onNext: () => void; onSeek: (seconds: number) => void; onClose: () => void; onLoadSimilar: () => void; onAnalyze: () => void; onPlaySimilar: (track: Track, queue: Track[]) => void }) {
  const [view, setView] = useState<PlayerView>('cover')
  const gestureStart = useRef<{ x: number; y: number } | null>(null)
  const dialogRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose
  const palette = useArtworkPalette(player.current?.artworkUrl, player.current?.id)
  const analysis = player.current?.analysis
  const temporaryOnlineTrack = player.current?.id.toLowerCase().startsWith('online-') ?? false
  useEffect(() => setView('cover'), [player.current?.id])
  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    const previousFocus = document.activeElement as HTMLElement | null
    const siblings = [...(dialog.parentElement?.children ?? [])].filter((element) => element !== dialog) as HTMLElement[]
    const previouslyInert = new Set(siblings.filter((element) => element.inert))
    siblings.forEach((element) => { element.inert = true })
    closeRef.current?.focus()
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); onCloseRef.current(); return }
      if (event.key !== 'Tab') return
      const focusable = [...dialog.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])')]
      if (!focusable.length) { event.preventDefault(); dialog.focus(); return }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }
    dialog.addEventListener('keydown', onKeyDown)
    return () => {
      dialog.removeEventListener('keydown', onKeyDown)
      siblings.forEach((element) => { if (!previouslyInert.has(element)) element.inert = false })
      previousFocus?.focus()
    }
  }, [])
  const selectView = (next: PlayerView) => {
    setView(next)
    if (next === 'similar') onLoadSimilar()
  }
  const finishGesture = (x: number, y: number) => {
    const start = gestureStart.current
    gestureStart.current = null
    if (!start) return
    const horizontal = x - start.x
    const vertical = y - start.y
    if (vertical < -64 && Math.abs(vertical) > Math.abs(horizontal)) selectView('similar')
    else if (Math.abs(horizontal) > 64) horizontal < 0 ? onNext() : onPrevious()
  }
  const style = {
    '--player-color': palette,
    '--player-artwork': player.current?.artworkUrl ? `url("${player.current.artworkUrl}")` : 'none',
  } as CSSProperties
  return <div ref={dialogRef} className="now-playing" style={style} role="dialog" aria-modal="true" aria-labelledby="now-playing-title" tabIndex={-1}>
    <div className="now-backdrop" aria-hidden="true" />
    <header className="now-top"><button ref={closeRef} className="close-now-playing" onClick={onClose} aria-label="关闭"><Icon name="back" /></button><div className="now-heading"><strong id="now-playing-title">正在播放</strong><span>{player.current?.album || 'NAS 音乐'}</span></div><button className={`icon-button ${favorite ? 'favorite' : ''}`} onClick={onFavorite} aria-label={favorite ? '取消收藏' : '收藏'}><Icon name="heart" /></button></header>
    <div className="now-tabs" role="tablist" aria-label="播放器视图">{([['cover', '封面'], ['radar', '音乐画像'], ['similar', '相似音乐']] as const).map(([id, label]) => <button key={id} role="tab" aria-selected={view === id} className={view === id ? 'active' : ''} onClick={() => selectView(id)}>{label}</button>)}</div>
    <div className={`now-stage ${view}`} onPointerDown={(event) => { gestureStart.current = { x: event.clientX, y: event.clientY } }} onPointerUp={(event) => finishGesture(event.clientX, event.clientY)} onPointerCancel={() => { gestureStart.current = null }}>
      {view === 'cover' && <div className="now-cover"><AlbumArt title={player.current?.title ?? 'SHiNe'} artworkUrl={player.current?.artworkUrl} hero /><span className="swipe-hint">左右滑动切歌 · 上滑看相似音乐</span></div>}
      {view === 'radar' && <div className="now-radar"><RadarChart analysis={analysis} />{analysis?.status !== 'completed' && <button className="primary-button" onClick={onAnalyze}>{temporaryOnlineTrack ? '先下载入库后分析' : analysis?.status === 'queued' || analysis?.status === 'running' ? `分析中 ${Math.round((analysis.progress || 0) * 100)}%` : '分析这首歌'}</button>}</div>}
      {view === 'similar' && <SimilarMusic similar={similar} loading={similarLoading} onPlay={onPlaySimilar} />}
    </div>
    <section className="now-transport-area">
      <div className="now-meta"><span><h2>{player.current?.title || '还没有播放音乐'}</h2><p>{player.current?.artist || '从曲库选一首歌开始'}</p></span><AnalysisChips track={player.current} /></div>
      <div className="now-progress"><input aria-label="播放进度" type="range" min="0" max={player.duration || 1} value={Math.min(player.position, player.duration || 1)} onChange={(event) => onSeek(Number(event.target.value))} /><span>{formatTime(player.position)}</span><span>{formatTime(player.duration)}</span></div>
      <div className="now-controls"><button onClick={onPrevious} aria-label="上一首"><Icon name="previous" /></button><button className="player-toggle" onClick={onToggle}><Icon name={player.playing ? 'pause' : 'play'} /><span>{player.playing ? '暂停' : '播放'}</span></button><button onClick={onNext} aria-label="下一首"><Icon name="next" /></button></div>
    </section>
  </div>
}

export function AnalysisChips({ track, compact = false }: { track: Track | null; compact?: boolean }) {
  const analysis = track?.analysis
  if (!analysis || analysis.status !== 'completed') return <span className={`analysis-chips ${compact ? 'compact' : ''}`}><i>待分析</i></span>
  return <span className={`analysis-chips ${compact ? 'compact' : ''}`}>{analysis.bpm && <i>{Math.round(analysis.bpm)} BPM</i>}{analysis.keyName && <i>{analysis.keyName}</i>}{analysis.camelot && <i>{analysis.camelot}</i>}</span>
}

function SimilarMusic({ similar, loading, onPlay }: { similar: SimilarTracksResponse | null; loading: boolean; onPlay: (track: Track, queue: Track[]) => void }) {
  const tracks = similar?.items.map((item) => item.track) ?? []
  if (loading) return <div className="similar-loading"><Icon name="sparkles" /><strong>正在延续这段听感…</strong></div>
  if (!tracks.length) return <div className="similar-loading"><Icon name="radar" /><strong>暂时没有相似曲目</strong><span>先完成曲库分析，再来这里继续播放。</span></div>
  return <div className="now-similar-list">{similar?.items.map((item) => <button key={item.track.id} onClick={() => onPlay(item.track, tracks)}><AlbumArt title={item.track.title} artworkUrl={item.track.artworkUrl} small /><span><strong>{item.track.title}</strong><small>{item.track.artist}</small><AnalysisChips track={item.track} compact /></span><em>{item.similarityPercent}%</em><RadarChart analysis={item.track.analysis} compact /></button>)}</div>
}

function useArtworkPalette(artworkUrl?: string | null, seed = 'shine') {
  const fallback = useMemo(() => `hsl(${hashHue(seed || 'shine')} 34% 30%)`, [seed])
  const [color, setColor] = useState(fallback)
  useEffect(() => {
    setColor(fallback)
    if (!artworkUrl) return
    let cancelled = false
    const image = new Image()
    image.crossOrigin = 'anonymous'
    image.onload = () => {
      try {
        const canvas = document.createElement('canvas')
        canvas.width = 12; canvas.height = 12
        const context = canvas.getContext('2d', { willReadFrequently: true })
        if (!context) return
        context.drawImage(image, 0, 0, 12, 12)
        const pixels = context.getImageData(0, 0, 12, 12).data
        let r = 0; let g = 0; let b = 0; let count = 0
        for (let index = 0; index < pixels.length; index += 16) { r += pixels[index]; g += pixels[index + 1]; b += pixels[index + 2]; count++ }
        if (!cancelled && count) setColor(`rgb(${Math.round(r / count)} ${Math.round(g / count)} ${Math.round(b / count)})`)
      } catch { /* deterministic fallback remains when canvas is unavailable */ }
    }
    image.src = artworkUrl
    return () => { cancelled = true }
  }, [artworkUrl, fallback])
  return color
}

function hashHue(value: string) {
  let hash = 0
  for (const char of value) hash = (hash * 31 + char.charCodeAt(0)) | 0
  return Math.abs(hash) % 360
}
