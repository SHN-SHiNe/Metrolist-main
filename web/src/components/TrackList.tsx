import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { TRACK_ROW_HEIGHT, visibleTrackRange } from '../libraryPaging'
import type { Track } from '../types'
import { Icon } from './Icon'

export function AlbumArt({ title, artworkUrl, small, hero }: { title: string; artworkUrl?: string | null; small?: boolean; hero?: boolean }) {
  return <span className={`album-art ${small ? 'small' : ''} ${hero ? 'hero' : ''}`} aria-hidden="true">{artworkUrl && <img src={artworkUrl} alt="" onError={(event) => event.currentTarget.remove()} />}<span>♪</span><i>{title.slice(0, 1).toUpperCase()}</i></span>
}

export function EmptyState({ title, body, compact }: { title: string; body: string; compact?: boolean }) {
  return <div className={`empty-state ${compact ? 'compact' : ''}`}><span>♫</span><strong>{title}</strong><p>{body}</p></div>
}

export function TrackSection({ title, subtitle, tracks, favorites, onPlay, onFavorite }: { title: string; subtitle: string; tracks: Track[]; favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite?: (track: Track) => void }) {
  return <section className="track-section"><div className="section-heading"><div><h2>{title}</h2><p>{subtitle}</p></div>{tracks.length > 0 && <button className="round-play" onClick={() => onPlay(tracks[0], tracks)} aria-label={`播放${title}`}><Icon name="play" /></button>}</div>{tracks.length ? <TrackList tracks={tracks} favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} /> : <EmptyState title="这里还没有音乐" body="扫描 NAS、在线下载或继续收藏后会显示在这里。" />}</section>
}

export function AlbumRow({ title, tracks, onPlay, badges }: { title: string; tracks: Track[]; onPlay: (track: Track, queue: Track[]) => void; badges?: Map<string, string> }) {
  if (!tracks.length) return null
  const rail = <div className="album-row" aria-label={title || '推荐曲目'}>{tracks.map((track) => <button key={track.id} className="album-item" onClick={() => onPlay(track, tracks)}><AlbumArt title={track.title} artworkUrl={track.artworkUrl} />{badges?.get(track.id) && <span className="album-badge">{badges.get(track.id)}</span>}<strong>{track.title}</strong><span>{track.artist}</span></button>)}</div>
  if (!title) return rail
  return <section className="content-rail"><div className="section-heading"><h2>{title}</h2></div>{rail}</section>
}

export function TrackList({ tracks, favorites, onPlay, onFavorite, trailingAction, selectedIds, onSelection, sort, onSort, virtualized = false }: { tracks: Track[]; favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite?: (track: Track) => void; trailingAction?: (track: Track) => ReactNode; selectedIds?: string[]; onSelection?: (ids: string[]) => void; sort?: 'title' | 'artist' | 'album'; onSort?: (value: 'title' | 'artist' | 'album') => void; virtualized?: boolean }) {
  const selectable = Boolean(onSelection && selectedIds)
  const favoriteIds = useMemo(() => new Set(favorites.map((track) => track.id)), [favorites])
  const viewport = useRef<HTMLDivElement>(null)
  const [scrollTop, setScrollTop] = useState(0)
  const [viewportHeight, setViewportHeight] = useState(620)
  const toggle = (id: string) => onSelection?.(selectedIds?.includes(id) ? selectedIds.filter((value) => value !== id) : [...(selectedIds ?? []), id])
  useEffect(() => {
    if (!virtualized || !viewport.current) return
    const element = viewport.current
    const observer = new ResizeObserver(() => setViewportHeight(element.clientHeight))
    observer.observe(element)
    setViewportHeight(element.clientHeight)
    return () => observer.disconnect()
  }, [virtualized])
  const range = visibleTrackRange(scrollTop, viewportHeight, TRACK_ROW_HEIGHT, tracks.length)
  const row = (track: Track, index: number, positioned = false) => <TrackRow key={track.id} track={track} index={index} tracks={tracks} favorite={favoriteIds.has(track.id)} selectable={selectable} selected={selectedIds?.includes(track.id) ?? false} onToggle={toggle} onPlay={onPlay} onFavorite={onFavorite} trailingAction={trailingAction} positioned={positioned} />
  return <div className={`track-list ${virtualized ? 'virtualized' : ''}`} role="list"><div className="track-header"><span>{selectable ? <label className="track-select"><input type="checkbox" aria-label="全选当前曲目" checked={selectedIds?.length === tracks.length && tracks.length > 0} onChange={(event) => onSelection?.(event.target.checked ? tracks.map((track) => track.id) : [])} /></label> : '#'}</span><button disabled={!onSort} onClick={() => onSort?.(sort === 'title' ? 'artist' : 'title')}>标题 / 歌手{sort === 'title' ? ' ↑' : sort === 'artist' ? ' ↓' : ''}</button><button disabled={!onSort} onClick={() => onSort?.('album')}>专辑{sort === 'album' ? ' ↑' : ''}</button><span>时长</span><span /></div>{virtualized ? <div className="track-viewport" ref={viewport} onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}><div className="track-virtual-space" style={{ height: tracks.length * TRACK_ROW_HEIGHT }}>{tracks.slice(range.start, range.end).map((track, offset) => row(track, range.start + offset, true))}</div></div> : tracks.map((track, index) => row(track, index))}</div>
}

function TrackRow({ track, index, tracks, favorite, selectable, selected, onToggle, onPlay, onFavorite, trailingAction, positioned }: { track: Track; index: number; tracks: Track[]; favorite: boolean; selectable: boolean; selected: boolean; onToggle: (id: string) => void; onPlay: (track: Track, queue: Track[]) => void; onFavorite?: (track: Track) => void; trailingAction?: (track: Track) => ReactNode; positioned: boolean }) {
  return <div className={`track-row ${positioned ? 'positioned' : ''}`} style={positioned ? { transform: `translateY(${index * TRACK_ROW_HEIGHT}px)` } : undefined} role="listitem">{selectable ? <label className="track-select"><input type="checkbox" checked={selected} onChange={() => onToggle(track.id)} aria-label={`选择 ${track.title}`} /></label> : <button className="track-play" onClick={() => onPlay(track, tracks)} aria-label={`播放 ${track.title}`}>{index + 1}<span className="hover-play">▶</span></button>}<button className="track-identity" onClick={() => onPlay(track, tracks)} aria-label={`播放 ${track.title}`}><AlbumArt title={track.title} artworkUrl={track.artworkUrl} small /><span><strong>{track.title}</strong><small>{track.artist}</small></span></button><span className="track-album">{track.album || track.source || '本地音乐'}</span><span className="track-duration">{formatTime(track.durationMs / 1000)}</span><span className="track-actions">{onFavorite && <button className={`icon-button ${favorite ? 'favorite' : ''}`} onClick={() => onFavorite(track)} aria-label={favorite ? '取消收藏' : '收藏'}><Icon name="heart" /></button>}{trailingAction?.(track)}</span></div>
}

export function formatTime(seconds: number) {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  return `${Math.floor(seconds / 60)}:${Math.floor(seconds % 60).toString().padStart(2, '0')}`
}
