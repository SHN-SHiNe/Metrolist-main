import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import type { SimilarTracksResponse, Track } from '../types'
import type { PlayerController } from '../usePlayer'
import { Icon } from '../components/Icon'
import { RadarChart } from '../components/RadarChart'
import { AlbumArt, formatTime } from '../components/TrackList'
import { activeLyricIndex, lyricMatches, parseLyrics, seekTimeForLyric, selectedLyricText } from '../lyrics'

type PlayerView = 'cover' | 'radar' | 'lyrics' | 'queue' | 'similar'

export function NowPlaying({ player, favorite, similar, similarLoading, onFavorite, onToggle, onPrevious, onNext, onSeek, onClose, onLoadSimilar, onAnalyze, onPlaySimilar, onMoveQueue, onRemoveQueue }: { player: PlayerController; favorite: boolean; similar: SimilarTracksResponse | null; similarLoading: boolean; onFavorite: () => void; onToggle: () => void; onPrevious: () => void; onNext: () => void; onSeek: (seconds: number) => void; onClose: () => void; onLoadSimilar: () => void; onAnalyze: () => void; onPlaySimilar: (track: Track, queue: Track[]) => void; onMoveQueue: (index: number, delta: -1 | 1) => void; onRemoveQueue: (index: number) => void }) {
  const [view, setView] = useState<PlayerView>('cover')
  const gestureStart = useRef<{ x: number; y: number; at: number } | null>(null)
  const longPressTimer = useRef<number | null>(null)
  const longPressed = useRef(false)
  const dialogRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose
  const palette = useArtworkPalette(player.current?.artworkUrl, player.current?.id)
  const analysis = player.current?.analysis
  const temporaryOnlineTrack = player.current?.id.toLowerCase().startsWith('online-') ?? false
  useEffect(() => setView('cover'), [player.current?.id])
  useEffect(() => () => {
    if (longPressTimer.current !== null) window.clearTimeout(longPressTimer.current)
  }, [])
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
    if (longPressTimer.current !== null) window.clearTimeout(longPressTimer.current)
    longPressTimer.current = null
    if (!start) return
    if (longPressed.current) { longPressed.current = false; return }
    const horizontal = x - start.x
    const vertical = y - start.y
    if (vertical < -64 && Math.abs(vertical) > Math.abs(horizontal)) selectView('similar')
    else if (vertical > 72 && Math.abs(vertical) > Math.abs(horizontal)) onClose()
    else if (Math.abs(horizontal) > 64) horizontal < 0 ? onNext() : onPrevious()
    else if (Math.hypot(horizontal, vertical) < 12 && Date.now() - start.at < 500 && (view === 'cover' || view === 'radar')) setView(view === 'cover' ? 'radar' : 'cover')
  }
  const startGesture = (x: number, y: number) => {
    gestureStart.current = { x, y, at: Date.now() }
    longPressed.current = false
    if (view === 'cover' || view === 'radar') longPressTimer.current = window.setTimeout(() => {
      longPressed.current = true
      onAnalyze()
    }, 620)
  }
  const cancelGesture = () => {
    gestureStart.current = null
    if (longPressTimer.current !== null) window.clearTimeout(longPressTimer.current)
    longPressTimer.current = null
  }
  const style = {
    '--player-color': palette,
    '--player-artwork': player.current?.artworkUrl ? `url("${player.current.artworkUrl}")` : 'none',
  } as CSSProperties
  return <div ref={dialogRef} className="now-playing" style={style} role="dialog" aria-modal="true" aria-labelledby="now-playing-title" tabIndex={-1}>
    <div className="now-backdrop" aria-hidden="true" />
    <header className="now-top"><button ref={closeRef} className="close-now-playing" onClick={onClose} aria-label="关闭"><Icon name="back" /></button><div className="now-heading"><strong id="now-playing-title">正在播放</strong><span>{player.current?.album || 'NAS 音乐'}</span></div><button className={`icon-button ${favorite ? 'favorite' : ''}`} onClick={onFavorite} aria-label={favorite ? '取消收藏' : '收藏'}><Icon name="heart" /></button></header>
    <div className={`now-stage ${view}`} onPointerDown={(event) => { if (!(event.target as HTMLElement).closest('button,input,textarea')) startGesture(event.clientX, event.clientY) }} onPointerUp={(event) => finishGesture(event.clientX, event.clientY)} onPointerCancel={cancelGesture} onPointerLeave={(event) => { const start = gestureStart.current; if (start && Math.hypot(event.clientX - start.x, event.clientY - start.y) > 18 && longPressTimer.current !== null) { window.clearTimeout(longPressTimer.current); longPressTimer.current = null } }} onDoubleClick={(event) => { if ((event.target as HTMLElement).closest('button,input,textarea')) return; const bounds = event.currentTarget.getBoundingClientRect(); onSeek(Math.max(0, Math.min(player.duration, player.position + (event.clientX < bounds.left + bounds.width / 2 ? -10 : 10)))) }}>
      {view === 'cover' && <div className="now-cover"><AlbumArt title={player.current?.title ?? 'SHiNe'} artworkUrl={player.current?.artworkUrl} hero /><span className="swipe-hint">左右滑动切歌 · 上滑看相似音乐</span></div>}
      {view === 'radar' && <div className="now-radar"><RadarChart analysis={analysis} />{analysis?.status !== 'completed' && <button className="primary-button" onClick={onAnalyze}>{temporaryOnlineTrack ? '先下载入库后分析' : analysis?.status === 'queued' || analysis?.status === 'running' ? `分析中 ${Math.round((analysis.progress || 0) * 100)}%` : '分析这首歌'}</button>}</div>}
      {view === 'lyrics' && <LyricsPanel track={player.current} positionSeconds={player.position} onSeek={onSeek} />}
      {view === 'queue' && <QueueEditor tracks={player.queue} currentIndex={player.index} playing={player.playing} onPlay={(track) => onPlaySimilar(track, player.queue)} onMove={onMoveQueue} onRemove={onRemoveQueue} />}
      {view === 'similar' && <SimilarMusic similar={similar} loading={similarLoading} onPlay={onPlaySimilar} />}
    </div>
    <section className="now-transport-area">
      <div className="now-meta"><span><h2>{player.current?.title || '还没有播放音乐'}</h2><p>{player.current?.artist || '从曲库选一首歌开始'}</p></span><AnalysisChips track={player.current} /></div>
      <div className="now-progress"><input aria-label="播放进度" type="range" min="0" max={player.duration || 1} value={Math.min(player.position, player.duration || 1)} onChange={(event) => onSeek(Number(event.target.value))} /><span>{formatTime(player.position)}</span><span>{formatTime(player.duration)}</span></div>
      <div className="now-controls"><button onClick={onPrevious} aria-label="上一首"><Icon name="previous" /></button><button className="player-toggle" onClick={onToggle}><Icon name={player.playing ? 'pause' : 'play'} /><span>{player.playing ? '暂停' : '播放'}</span></button><button onClick={onNext} aria-label="下一首"><Icon name="next" /></button></div>
      <div className="now-tabs" role="tablist" aria-label="播放器工具栏">{([['queue', '队列', 'queue', '队列'], ['similar', '相似', 'sparkles', '相似音乐'], ['cover', '封面', 'album', '封面'], ['radar', '画像', 'radar', '音乐画像'], ['lyrics', '歌词', 'playlist', '歌词']] as const).map(([id, label, icon, accessibleLabel]) => <button key={id} role="tab" aria-label={accessibleLabel} aria-selected={view === id} className={view === id ? 'active' : ''} onClick={() => selectView(id)}><Icon name={icon} /><span>{label}</span></button>)}</div>
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

export function LyricsPanel({ track, positionSeconds, onSeek }: { track: Track | null; positionSeconds?: number; onSeek?: (seconds: number) => void }) {
  const [editing, setEditing] = useState(false)
  const [lyrics, setLyrics] = useState('')
  const [draft, setDraft] = useState('')
  const [query, setQuery] = useState('')
  const [offsetMs, setOffsetMs] = useState(0)
  const [selecting, setSelecting] = useState(false)
  const [selectedLines, setSelectedLines] = useState<Set<number>>(() => new Set())
  const [selectionNotice, setSelectionNotice] = useState('')
  const lineRefs = useRef(new Map<number, HTMLElement>())
  useEffect(() => {
    const stored = track ? localStorage.getItem(`shine-lyrics:${track.id}`) ?? '' : ''
    setLyrics(stored)
    setDraft(stored)
    setQuery('')
    setSelecting(false)
    setSelectedLines(new Set())
    setSelectionNotice('')
    const storedOffset = track ? Number(localStorage.getItem(`shine-lyrics-offset:${track.id}`) ?? 0) : 0
    setOffsetMs(Number.isFinite(storedOffset) ? Math.max(-10000, Math.min(10000, storedOffset)) : 0)
    setEditing(false)
  }, [track?.id])
  const parsed = useMemo(() => parseLyrics(lyrics), [lyrics])
  const effectiveOffsetMs = parsed.embeddedOffsetMs + offsetMs
  const activeIndex = positionSeconds == null ? -1 : activeLyricIndex(parsed.lines, positionSeconds, effectiveOffsetMs)
  const visibleLines = useMemo(() => parsed.lines.map((line, index) => ({ line, index })).filter(({ line }) => lyricMatches(line, query)), [parsed.lines, query])
  useEffect(() => {
    if (activeIndex < 0 || query) return
    const active = lineRefs.current.get(activeIndex)
    if (!active) return
    const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    active.scrollIntoView({ block: 'center', behavior: reducedMotion ? 'auto' : 'smooth' })
  }, [activeIndex, query])
  const save = () => {
    if (!track) return
    const value = draft.trim()
    if (value) localStorage.setItem(`shine-lyrics:${track.id}`, value)
    else localStorage.removeItem(`shine-lyrics:${track.id}`)
    setLyrics(value)
    setEditing(false)
  }
  const changeOffset = (next: number) => {
    if (!track) return
    const clamped = Math.max(-10000, Math.min(10000, Math.round(next / 100) * 100))
    setOffsetMs(clamped)
    if (clamped) localStorage.setItem(`shine-lyrics-offset:${track.id}`, String(clamped))
    else localStorage.removeItem(`shine-lyrics-offset:${track.id}`)
  }
  const jumpToLine = (index: number) => {
    const seconds = seekTimeForLyric(parsed.lines[index], effectiveOffsetMs)
    if (seconds != null) onSeek?.(seconds)
  }
  const toggleSelectedLine = (index: number) => setSelectedLines((current) => {
    const next = new Set(current)
    if (next.has(index)) next.delete(index); else next.add(index)
    return next
  })
  const finishSelecting = () => {
    setSelecting(false)
    setSelectedLines(new Set())
    setSelectionNotice('')
  }
  const copySelection = async (message = '所选歌词已复制') => {
    const text = selectedLyricText(parsed.lines, selectedLines)
    if (!text) return false
    try {
      await copyText(text)
      setSelectionNotice(message)
      return true
    } catch {
      setSelectionNotice('复制失败，请稍后重试')
      return false
    }
  }
  const shareSelection = async () => {
    const text = selectedLyricText(parsed.lines, selectedLines)
    if (!text || typeof navigator.share !== 'function') return
    try {
      await navigator.share({ title: track ? `${track.title} · ${track.artist}` : 'SHiNe MUSIC 歌词', text })
      setSelectionNotice('已打开系统分享')
    } catch {
      await copySelection('分享未完成，歌词已复制')
    }
  }
  if (!track) return <div className="player-panel-empty"><Icon name="playlist" /><strong>还没有正在播放的歌曲</strong><span>播放一首歌后，这里会显示歌词。</span></div>
  return <section className="lyrics-panel" aria-label={`${track.title} 的歌词`}>
    <header><span><strong>歌词</strong><small>{track.title} · {track.artist}{parsed.timed ? ' · 同步歌词' : ''}</small></span>{!editing && lyrics && <span className="lyrics-header-actions">{selecting ? <button className="secondary-button" onClick={finishSelecting}>完成</button> : <><button className="secondary-button" onClick={() => { setSelecting(true); setSelectionNotice('') }}>选择</button><button className="secondary-button" onClick={() => { finishSelecting(); setEditing(true) }}>编辑</button></>}</span>}</header>
    {editing ? <div className="lyrics-editor"><label htmlFor={`lyrics-${track.id}`}>歌词文本</label><textarea id={`lyrics-${track.id}`} value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="粘贴普通歌词，或带 [00:12.34] 时间戳的 LRC 歌词。" /><p>支持 LRC 时间戳与 [offset:毫秒]；歌词仅保存在这台浏览器，NAS 共享歌词接口接入后可迁移。</p><div><button className="secondary-button" onClick={() => { setDraft(lyrics); setEditing(false) }}>取消</button><button className="primary-button" onClick={save}>保存歌词</button></div></div> : lyrics ? <>
      <div className="lyrics-tools">
        <label className="lyrics-search"><Icon name="search" /><input aria-label="搜索歌词" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索歌词" />{query && <button onClick={() => setQuery('')} aria-label="清除歌词搜索"><Icon name="close" /></button>}</label>
        {parsed.timed && <div className="lyrics-offset" aria-label="整首歌词时间偏移"><button onClick={() => changeOffset(offsetMs - 500)} aria-label="歌词提前半秒">−0.5s</button><output aria-live="polite" title="正值让歌词稍后出现">偏移 {formatOffset(offsetMs)}</output><button onClick={() => changeOffset(offsetMs + 500)} aria-label="歌词延后半秒">+0.5s</button>{offsetMs !== 0 && <button className="lyrics-offset-reset" onClick={() => changeOffset(0)}>归零</button>}</div>}
      </div>
      {selecting && <div className="lyrics-selection-bar"><span aria-live="polite">已选择 {selectedLines.size} 行{selectionNotice ? ` · ${selectionNotice}` : ''}</span><button onClick={() => setSelectedLines(new Set(visibleLines.map(({ index }) => index)))} disabled={!visibleLines.length}>全选显示</button><button onClick={() => void copySelection()} disabled={!selectedLines.size}>复制</button>{typeof navigator.share === 'function' && <button onClick={() => void shareSelection()} disabled={!selectedLines.size}>分享</button>}</div>}
      {visibleLines.length ? <div className={`lyrics-lines ${parsed.timed ? 'timed' : 'plain'}`} aria-live="off">{visibleLines.map(({ line, index }) => {
        const className = `${index === activeIndex ? 'current' : index < activeIndex ? 'past' : ''} ${selectedLines.has(index) ? 'selected' : ''}`.trim()
        if (selecting || (line.timeSeconds != null && onSeek)) return <button key={line.id} ref={(element) => { if (element) lineRefs.current.set(index, element); else lineRefs.current.delete(index) }} className={className} aria-current={index === activeIndex ? 'true' : undefined} aria-pressed={selecting ? selectedLines.has(index) : undefined} onClick={() => selecting ? toggleSelectedLine(index) : jumpToLine(index)}><span>{line.text}</span>{line.timeSeconds != null && <time>{formatTime((line.timeSeconds ?? 0) + effectiveOffsetMs / 1000)}</time>}</button>
        return <p key={line.id} ref={(element) => { if (element) lineRefs.current.set(index, element); else lineRefs.current.delete(index) }} className={className} aria-current={index === activeIndex ? 'true' : undefined}>{line.text}</p>
      })}</div> : <div className="lyrics-no-match"><strong>没有找到“{query}”</strong><button className="secondary-button" onClick={() => setQuery('')}>清除搜索</button></div>}
    </> : <div className="player-panel-empty"><Icon name="playlist" /><strong>这首歌还没有歌词</strong><span>可以添加普通文本或 LRC 同步歌词。</span><button className="primary-button" onClick={() => setEditing(true)}>添加歌词</button></div>}
  </section>
}

function formatOffset(milliseconds: number) {
  if (!milliseconds) return '0.0s'
  return `${milliseconds > 0 ? '+' : '−'}${(Math.abs(milliseconds) / 1000).toFixed(1)}s`
}

async function copyText(value: string) {
  if (navigator.clipboard?.writeText) {
    try { await navigator.clipboard.writeText(value); return } catch { /* HTTP LAN fallback below */ }
  }
  const input = document.createElement('textarea')
  input.value = value
  input.setAttribute('readonly', '')
  input.style.position = 'fixed'
  input.style.opacity = '0'
  document.body.appendChild(input)
  input.select()
  const copied = document.execCommand('copy')
  input.remove()
  if (!copied) throw new Error('clipboard unavailable')
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
