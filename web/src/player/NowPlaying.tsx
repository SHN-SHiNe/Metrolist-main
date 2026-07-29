import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import type { SimilarTracksResponse, Track } from '../types'
import type { PlayerController } from '../usePlayer'
import { Icon } from '../components/Icon'
import { RadarChart } from '../components/RadarChart'
import { AlbumArt, formatTime } from '../components/TrackList'

type PlayerView = 'cover' | 'radar' | 'lyrics' | 'queue' | 'similar'

export function NowPlaying({ player, favorite, similar, similarLoading, onFavorite, onToggle, onPrevious, onNext, onSeek, onClose, onLoadSimilar, onAnalyze, onPlaySimilar, onMoveQueue, onRemoveQueue }: { player: PlayerController; favorite: boolean; similar: SimilarTracksResponse | null; similarLoading: boolean; onFavorite: () => void; onToggle: () => void; onPrevious: () => void; onNext: () => void; onSeek: (seconds: number) => void; onClose: () => void; onLoadSimilar: () => void; onAnalyze: () => void; onPlaySimilar: (track: Track, queue: Track[]) => void; onMoveQueue: (index: number, delta: -1 | 1) => void; onRemoveQueue: (index: number) => void }) {
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
    <div className="now-tabs" role="tablist" aria-label="播放器视图">{([['cover', '封面', '封面'], ['lyrics', '歌词', '歌词'], ['queue', '队列', '队列'], ['radar', '画像', '音乐画像'], ['similar', '相似', '相似音乐']] as const).map(([id, label, accessibleLabel]) => <button key={id} role="tab" aria-label={accessibleLabel} aria-selected={view === id} className={view === id ? 'active' : ''} onClick={() => selectView(id)}>{label}</button>)}</div>
    <div className={`now-stage ${view}`} onPointerDown={(event) => { gestureStart.current = { x: event.clientX, y: event.clientY } }} onPointerUp={(event) => finishGesture(event.clientX, event.clientY)} onPointerCancel={() => { gestureStart.current = null }}>
      {view === 'cover' && <div className="now-cover"><AlbumArt title={player.current?.title ?? 'SHiNe'} artworkUrl={player.current?.artworkUrl} hero /><span className="swipe-hint">左右滑动切歌 · 上滑看相似音乐</span></div>}
      {view === 'radar' && <div className="now-radar"><RadarChart analysis={analysis} />{analysis?.status !== 'completed' && <button className="primary-button" onClick={onAnalyze}>{temporaryOnlineTrack ? '先下载入库后分析' : analysis?.status === 'queued' || analysis?.status === 'running' ? `分析中 ${Math.round((analysis.progress || 0) * 100)}%` : '分析这首歌'}</button>}</div>}
      {view === 'lyrics' && <LyricsPanel track={player.current} />}
      {view === 'queue' && <QueueEditor tracks={player.queue} currentIndex={player.index} playing={player.playing} onPlay={(track) => onPlaySimilar(track, player.queue)} onMove={onMoveQueue} onRemove={onRemoveQueue} />}
      {view === 'similar' && <SimilarMusic similar={similar} loading={similarLoading} onPlay={onPlaySimilar} />}
    </div>
    <section className="now-transport-area">
      <div className="now-meta"><span><h2>{player.current?.title || '还没有播放音乐'}</h2><p>{player.current?.artist || '从曲库选一首歌开始'}</p></span><AnalysisChips track={player.current} /></div>
      <div className="now-progress"><input aria-label="播放进度" type="range" min="0" max={player.duration || 1} value={Math.min(player.position, player.duration || 1)} onChange={(event) => onSeek(Number(event.target.value))} /><span>{formatTime(player.position)}</span><span>{formatTime(player.duration)}</span></div>
      <div className="now-controls"><button onClick={onPrevious} aria-label="上一首"><Icon name="previous" /></button><button className="player-toggle" onClick={onToggle}><Icon name={player.playing ? 'pause' : 'play'} /><span>{player.playing ? '暂停' : '播放'}</span></button><button onClick={onNext} aria-label="下一首"><Icon name="next" /></button></div>
    </section>
  </div>
}

export function QueueEditor({ tracks, currentIndex, playing, onPlay, onMove, onRemove, compact = false }: { tracks: Track[]; currentIndex: number; playing: boolean; onPlay: (track: Track) => void; onMove: (index: number, delta: -1 | 1) => void; onRemove: (index: number) => void; compact?: boolean }) {
  const duration = tracks.reduce((total, track) => total + track.durationMs, 0)
  if (!tracks.length) return <div className="player-panel-empty"><Icon name="queue" /><strong>队列还是空的</strong><span>从曲库或搜索结果中选择一首歌。</span></div>
  return <section className={`queue-editor ${compact ? 'compact' : ''}`} aria-label="可编辑播放队列">
    <header><span><strong>播放队列</strong><small>{tracks.length} 首 · {formatTime(duration / 1000)}</small></span></header>
    <div className="queue-editor-list" role="list">{tracks.map((track, index) => <div key={`${track.id}-${index}`} className={`queue-editor-item ${index === currentIndex ? 'active' : ''}`} role="listitem">
      <button className="queue-track-button" onClick={() => onPlay(track)} aria-label={`播放 ${track.title}`}><span className="queue-number">{index === currentIndex && playing ? '▶' : index + 1}</span><AlbumArt title={track.title} artworkUrl={track.artworkUrl} small /><span><strong>{track.title}</strong><small>{track.artist}</small></span></button>
      <span className="queue-edit-actions"><button disabled={index === 0} onClick={() => onMove(index, -1)} aria-label={`上移 ${track.title}`}><Icon name="back" /></button><button disabled={index === tracks.length - 1} onClick={() => onMove(index, 1)} aria-label={`下移 ${track.title}`}><Icon name="back" /></button><button onClick={() => onRemove(index)} aria-label={`从队列移除 ${track.title}`}><Icon name="close" /></button></span>
    </div>)}</div>
  </section>
}

export function LyricsPanel({ track }: { track: Track | null }) {
  const [editing, setEditing] = useState(false)
  const [lyrics, setLyrics] = useState('')
  const [draft, setDraft] = useState('')
  useEffect(() => {
    const stored = track ? localStorage.getItem(`shine-lyrics:${track.id}`) ?? '' : ''
    setLyrics(stored)
    setDraft(stored)
    setEditing(false)
  }, [track?.id])
  const save = () => {
    if (!track) return
    const value = draft.trim()
    if (value) localStorage.setItem(`shine-lyrics:${track.id}`, value)
    else localStorage.removeItem(`shine-lyrics:${track.id}`)
    setLyrics(value)
    setEditing(false)
  }
  if (!track) return <div className="player-panel-empty"><Icon name="playlist" /><strong>还没有正在播放的歌曲</strong><span>播放一首歌后，这里会显示歌词。</span></div>
  return <section className="lyrics-panel" aria-label={`${track.title} 的歌词`}>
    <header><span><strong>歌词</strong><small>{track.title} · {track.artist}</small></span>{!editing && lyrics && <button className="secondary-button" onClick={() => setEditing(true)}>编辑</button>}</header>
    {editing ? <div className="lyrics-editor"><label htmlFor={`lyrics-${track.id}`}>歌词文本</label><textarea id={`lyrics-${track.id}`} value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="在这里粘贴歌词；当前版本仅保存在这台浏览器。" /><p>当前版本仅保存在这台浏览器；NAS 共享歌词接口接入后可无缝迁移。</p><div><button className="secondary-button" onClick={() => { setDraft(lyrics); setEditing(false) }}>取消</button><button className="primary-button" onClick={save}>保存歌词</button></div></div> : lyrics ? <div className="lyrics-lines">{lyrics.split(/\r?\n/).filter(Boolean).map((line, index) => <p key={`${index}-${line}`}>{line}</p>)}</div> : <div className="player-panel-empty"><Icon name="playlist" /><strong>这首歌还没有歌词</strong><span>可以先添加文本歌词，后续歌词服务返回内容时会在这里显示。</span><button className="primary-button" onClick={() => setEditing(true)}>添加歌词</button></div>}
  </section>
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
