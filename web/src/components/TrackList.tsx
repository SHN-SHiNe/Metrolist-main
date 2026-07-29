import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react'
import { TRACK_ROW_HEIGHT, visibleTrackRange } from '../libraryPaging'
import { useTrackActions, type TrackMenuAction } from '../trackActions'
import type { LibrarySort, LibrarySortDirection, Track } from '../types'
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

export function TrackList({ tracks, favorites, onPlay, onFavorite, trailingAction, selectedIds, onSelection, sort, sortDirection = 'asc', onSort, virtualized = false }: { tracks: Track[]; favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite?: (track: Track) => void; trailingAction?: (track: Track) => ReactNode; selectedIds?: string[]; onSelection?: (ids: string[]) => void; sort?: LibrarySort; sortDirection?: LibrarySortDirection; onSort?: (value: LibrarySort, direction: LibrarySortDirection) => void; virtualized?: boolean }) {
  const selectable = Boolean(onSelection && selectedIds)
  const actionFactory = useTrackActions()
  const favoriteIds = useMemo(() => new Set(favorites.map((track) => track.id)), [favorites])
  const viewport = useRef<HTMLDivElement>(null)
  const menuElement = useRef<HTMLDivElement>(null)
  const menuOpener = useRef<HTMLElement | null>(null)
  const longPress = useRef<{ pointerId: number; x: number; y: number; timer: number } | null>(null)
  const suppressClicksUntil = useRef(0)
  const focusFrame = useRef<number | null>(null)
  const listId = useId().replace(/:/g, '')
  const [scrollTop, setScrollTop] = useState(0)
  const [viewportHeight, setViewportHeight] = useState(620)
  const [activeIndex, setActiveIndex] = useState(0)
  const [menu, setMenu] = useState<{ track: Track; index: number; mode: 'sheet' | 'context'; left: number; top: number } | null>(null)
  const [submenuId, setSubmenuId] = useState<string | null>(null)
  const toggle = (id: string) => onSelection?.(selectedIds?.includes(id) ? selectedIds.filter((value) => value !== id) : [...(selectedIds ?? []), id])
  useEffect(() => {
    if (!virtualized || !viewport.current) return
    const element = viewport.current
    const observer = new ResizeObserver(() => setViewportHeight(element.clientHeight))
    observer.observe(element)
    setViewportHeight(element.clientHeight)
    return () => observer.disconnect()
  }, [virtualized])
  useEffect(() => {
    setActiveIndex((current) => Math.min(Math.max(0, current), Math.max(0, tracks.length - 1)))
  }, [tracks.length])
  useEffect(() => {
    if (!menu) return
    const frame = window.requestAnimationFrame(() => menuElement.current?.querySelector<HTMLElement>('button:not(:disabled)')?.focus())
    return () => window.cancelAnimationFrame(frame)
  }, [menu, submenuId])
  useEffect(() => () => {
    if (longPress.current) window.clearTimeout(longPress.current.timer)
    if (focusFrame.current !== null) window.cancelAnimationFrame(focusFrame.current)
  }, [])

  const cancelLongPress = () => {
    if (!longPress.current) return
    window.clearTimeout(longPress.current.timer)
    longPress.current = null
  }
  const openMenu = (track: Track, index: number, mode: 'sheet' | 'context', x: number, y: number, opener: HTMLElement) => {
    cancelLongPress()
    menuOpener.current = opener
    const left = mode === 'context' ? Math.min(Math.max(12, x), Math.max(12, window.innerWidth - 276)) : 0
    const menuHeight = Math.min(560, window.innerHeight - 24)
    const top = mode === 'context' ? Math.min(Math.max(12, y), Math.max(12, window.innerHeight - menuHeight - 12)) : 0
    setSubmenuId(null)
    setMenu({ track, index, mode, left, top })
  }
  const closeMenu = (restoreFocus = true) => {
    setMenu(null)
    setSubmenuId(null)
    if (restoreFocus) window.requestAnimationFrame(() => menuOpener.current?.focus({ preventScroll: true }))
  }
  const startLongPress = (event: ReactPointerEvent<HTMLDivElement>, track: Track, index: number) => {
    if (event.pointerType === 'mouse' || event.button !== 0) return
    cancelLongPress()
    const { pointerId, clientX: x, clientY: y, currentTarget } = event
    const timer = window.setTimeout(() => {
      longPress.current = null
      suppressClicksUntil.current = Date.now() + 900
      openMenu(track, index, 'sheet', x, y, currentTarget)
    }, 520)
    longPress.current = { pointerId, x, y, timer }
  }
  const moveLongPress = (event: ReactPointerEvent<HTMLDivElement>) => {
    const pending = longPress.current
    if (!pending || pending.pointerId !== event.pointerId) return
    if (Math.hypot(event.clientX - pending.x, event.clientY - pending.y) > 10) cancelLongPress()
  }
  const openContextMenu = (event: ReactMouseEvent<HTMLDivElement>, track: Track, index: number) => {
    event.preventDefault()
    event.stopPropagation()
    const sheet = window.innerWidth <= 780
    openMenu(track, index, sheet ? 'sheet' : 'context', event.clientX, event.clientY, event.currentTarget)
  }
  const suppressLongPressClick = (event: ReactMouseEvent<HTMLDivElement>) => {
    if (Date.now() >= suppressClicksUntil.current) return
    event.preventDefault()
    event.stopPropagation()
  }
  const focusTrack = (index: number, key: string) => {
    const element = viewport.current
    if (!element || !tracks.length) return
    const next = Math.min(Math.max(0, index), tracks.length - 1)
    const maxScroll = Math.max(0, tracks.length * TRACK_ROW_HEIGHT - element.clientHeight)
    const rowTop = next * TRACK_ROW_HEIGHT
    const rowBottom = rowTop + TRACK_ROW_HEIGHT
    let nextScroll = element.scrollTop
    if (key === 'Home') nextScroll = 0
    else if (key === 'End') nextScroll = maxScroll
    else if (key === 'PageDown') nextScroll = Math.min(maxScroll, element.scrollTop + element.clientHeight)
    else if (key === 'PageUp') nextScroll = Math.max(0, element.scrollTop - element.clientHeight)
    else if (rowTop < element.scrollTop) nextScroll = rowTop
    else if (rowBottom > element.scrollTop + element.clientHeight) nextScroll = rowBottom - element.clientHeight
    element.scrollTop = nextScroll
    setScrollTop(nextScroll)
    setActiveIndex(next)
    if (focusFrame.current !== null) window.cancelAnimationFrame(focusFrame.current)
    focusFrame.current = window.requestAnimationFrame(() => {
      focusFrame.current = null
      viewport.current?.querySelector<HTMLElement>(`[data-track-index="${next}"]`)?.focus({ preventScroll: true })
    })
  }
  const handleViewportKey = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (!virtualized || !tracks.length) return
    const page = Math.max(1, Math.floor(viewportHeight / TRACK_ROW_HEIGHT))
    const targets: Partial<Record<string, number>> = {
      ArrowDown: activeIndex + 1,
      ArrowUp: activeIndex - 1,
      PageDown: activeIndex + page,
      PageUp: activeIndex - page,
      Home: 0,
      End: tracks.length - 1,
    }
    const target = targets[event.key]
    if (target === undefined) return
    event.preventDefault()
    event.stopPropagation()
    focusTrack(target, event.key)
  }
  const handleMenuKey = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      closeMenu()
      return
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
    const items = [...(menuElement.current?.querySelectorAll<HTMLElement>('button:not(:disabled)') ?? [])]
    if (!items.length) return
    event.preventDefault()
    const current = Math.max(0, items.indexOf(document.activeElement as HTMLElement))
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length
    items[next].focus()
  }
  const range = visibleTrackRange(scrollTop, viewportHeight, TRACK_ROW_HEIGHT, tracks.length)
  const menuTrailing = menu ? trailingAction?.(menu.track) : null
  const fallbackActions: TrackMenuAction[] = menu && onFavorite ? [
    { id: 'favorite', label: favoriteIds.has(menu.track.id) ? '取消收藏' : '收藏', icon: 'heart', onSelect: () => onFavorite(menu.track) },
  ] : []
  const contextualActions = menu ? actionFactory?.(menu.track, tracks) ?? fallbackActions : []
  const rootActions: TrackMenuAction[] = menu ? [
    // Playback belongs to this list's public onPlay interface. In particular,
    // online lists use it to show the required confirmation before playback.
    { id: 'play', label: '播放', icon: 'play', onSelect: () => onPlay(menu.track, tracks) },
    ...contextualActions.filter((action) => action.id !== 'play'),
  ] : []
  const activeParent = submenuId ? rootActions.find((action) => action.id === submenuId) : null
  const visibleActions = activeParent?.children ?? rootActions
  const invokeAction = (action: TrackMenuAction) => {
    if (action.disabled) return
    if (action.children?.length) {
      setSubmenuId(action.id)
      return
    }
    closeMenu(false)
    void action.onSelect?.()
  }
  const requestSort = (value: LibrarySort) => onSort?.(value, sort === value ? (sortDirection === 'asc' ? 'desc' : 'asc') : 'asc')
  const row = (track: Track, index: number, positioned = false) => <TrackRow key={track.id} id={`${listId}-track-${index}`} track={track} index={index} tracks={tracks} favorite={favoriteIds.has(track.id)} selectable={selectable} selected={selectedIds?.includes(track.id) ?? false} active={virtualized && activeIndex === index} onActive={setActiveIndex} onToggle={toggle} onPlay={onPlay} onFavorite={onFavorite} trailingAction={trailingAction} positioned={positioned} onPointerDown={(event) => startLongPress(event, track, index)} onPointerMove={moveLongPress} onPointerEnd={cancelLongPress} onContextMenu={(event) => openContextMenu(event, track, index)} onClickCapture={suppressLongPressClick} />
  return <div className={`track-list ${virtualized ? 'virtualized' : ''}`} role="list"><div className="track-header"><span>{selectable ? <label className="track-select"><input type="checkbox" aria-label="全选当前曲目" checked={selectedIds?.length === tracks.length && tracks.length > 0} onChange={(event) => onSelection?.(event.target.checked ? tracks.map((track) => track.id) : [])} /></label> : '#'}</span><button disabled={!onSort} onClick={() => requestSort(sort === 'title' ? 'artist' : 'title')}>标题 / 歌手{sort === 'title' || sort === 'artist' ? (sortDirection === 'asc' ? ' ↑' : ' ↓') : ''}</button><button disabled={!onSort} onClick={() => requestSort('album')}>专辑{sort === 'album' ? (sortDirection === 'asc' ? ' ↑' : ' ↓') : ''}</button><span>时长</span><span /></div>{virtualized ? <div className="track-viewport" ref={viewport} tabIndex={0} aria-label="曲目列表，使用方向键、翻页键、首页键和末页键浏览" onKeyDown={handleViewportKey} onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}><div className="track-virtual-space" style={{ height: tracks.length * TRACK_ROW_HEIGHT }}>{tracks.slice(range.start, range.end).map((track, offset) => row(track, range.start + offset, true))}</div></div> : tracks.map((track, index) => row(track, index))}{menu && <div className="track-context-layer"><div className="track-context-backdrop" aria-hidden="true" onPointerDown={() => closeMenu()} /><div ref={menuElement} className={`track-context-menu ${menu.mode}`} style={menu.mode === 'context' ? { left: menu.left, top: menu.top } : undefined} role="menu" aria-label={`${menu.track.title} 的操作`} onKeyDown={handleMenuKey}><div className="track-context-heading" role="none"><AlbumArt title={menu.track.title} artworkUrl={menu.track.artworkUrl} small /><span><strong>{menu.track.title}</strong><small>{activeParent ? activeParent.label : menu.track.artist}</small></span></div>{activeParent && <button role="menuitem" onClick={() => setSubmenuId(null)}><Icon name="back" />返回歌曲操作</button>}{visibleActions.map((action) => <button key={action.id} role="menuitem" disabled={action.disabled} title={action.description} aria-haspopup={action.children?.length ? 'menu' : undefined} onClick={() => invokeAction(action)}>{action.icon && <Icon name={action.icon} />}<span>{action.label}</span>{action.children?.length ? <b className="track-menu-chevron">›</b> : null}</button>)}{!activeParent && menuTrailing && <div className="track-context-extra" role="none" onClickCapture={() => window.setTimeout(() => closeMenu(false), 0)}>{menuTrailing}</div>}<button role="menuitem" className="track-context-cancel" onClick={() => closeMenu()}><Icon name="close" />关闭</button></div></div>}</div>
}

function TrackRow({ id, track, index, tracks, favorite, selectable, selected, active, onActive, onToggle, onPlay, onFavorite, trailingAction, positioned, onPointerDown, onPointerMove, onPointerEnd, onContextMenu, onClickCapture }: { id: string; track: Track; index: number; tracks: Track[]; favorite: boolean; selectable: boolean; selected: boolean; active: boolean; onActive: (index: number) => void; onToggle: (id: string) => void; onPlay: (track: Track, queue: Track[]) => void; onFavorite?: (track: Track) => void; trailingAction?: (track: Track) => ReactNode; positioned: boolean; onPointerDown: (event: ReactPointerEvent<HTMLDivElement>) => void; onPointerMove: (event: ReactPointerEvent<HTMLDivElement>) => void; onPointerEnd: () => void; onContextMenu: (event: ReactMouseEvent<HTMLDivElement>) => void; onClickCapture: (event: ReactMouseEvent<HTMLDivElement>) => void }) {
  return <div id={id} data-track-index={index} tabIndex={-1} aria-label={`${index + 1}. ${track.title}，${track.artist}`} className={`track-row ${positioned ? 'positioned' : ''} ${active ? 'keyboard-active' : ''}`} style={positioned ? { transform: `translateY(${index * TRACK_ROW_HEIGHT}px)` } : undefined} role="listitem" onFocusCapture={() => onActive(index)} onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerEnd} onPointerCancel={onPointerEnd} onPointerLeave={onPointerEnd} onContextMenu={onContextMenu} onClickCapture={onClickCapture}>{selectable ? <label className="track-select"><input type="checkbox" checked={selected} onChange={() => onToggle(track.id)} aria-label={`选择 ${track.title}`} /></label> : <button className="track-play" onClick={() => onPlay(track, tracks)} aria-label={`播放 ${track.title}`}>{index + 1}<span className="hover-play">▶</span></button>}<button className="track-identity" onClick={() => onPlay(track, tracks)} aria-label={`播放 ${track.title}`}><AlbumArt title={track.title} artworkUrl={track.artworkUrl} small /><span><strong>{track.title}</strong><small>{track.artist}</small></span></button><span className="track-album">{track.album || track.source || '本地音乐'}</span><span className="track-duration">{formatTime(track.durationMs / 1000)}</span><span className="track-actions">{onFavorite && <button className={`icon-button ${favorite ? 'favorite' : ''}`} onClick={() => onFavorite(track)} aria-label={favorite ? '取消收藏' : '收藏'}><Icon name="heart" /></button>}{trailingAction?.(track)}</span></div>
}

export function formatTime(seconds: number) {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  return `${Math.floor(seconds / 60)}:${Math.floor(seconds % 60).toString().padStart(2, '0')}`
}
