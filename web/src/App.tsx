import { FormEvent, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { api } from './api'
import { LIBRARY_PAGE_SIZE, mergeTrackPage } from './libraryPaging'
import { desktopNavigation, sectionFromHash, type Section } from './navigation'
import { randomId } from './randomId'
import { pageFromScroll, pageOffset } from './pager'
import { appendRoomTrack, insertRoomTrackNext, moveRoomTrack, removeRoomTrack } from './roomQueue'
import { mergeSimilarQueue, recentTrackIds } from './similarAutoplay'
import { appendTrackOnce, isTemporaryOnlineTrack, TrackActionsProvider, type TrackMenuActionFactory } from './trackActions'
import type { AnalysisSummary, DownloadJob, HistoryEntry, LibrarySort, LibrarySortDirection, MusicLibrary, MusicLibraryInput, PlaylistDetail, PlaylistSummary, RoomSummary, SimilarTracksResponse, SourceConfig, Track } from './types'
import { usePlayer } from './usePlayer'
import { useRoomSync } from './useRoomSync'
import { AdvancedSearchPanel } from './components/AdvancedSearchPanel'
import { DownloadQueuePage } from './components/DownloadQueuePage'
import { Icon } from './components/Icon'
import { LibraryAnalysisView } from './components/LibraryAnalysisView'
import { OnlinePlaylistBrowser, sourceLabel } from './components/OnlinePlaylistBrowser'
import { OnlineConfirmationDialog } from './components/OnlineConfirmationDialog'
import { AlbumArt, AlbumRow, EmptyState, TrackList, TrackSection, formatTime } from './components/TrackList'
import { RadarChart } from './components/RadarChart'
import { DesktopShell, MobileShell } from './layout/Shells'
import { NowPlaying, AnalysisChips, LyricsPanel, QueueEditor } from './player/NowPlaying'
import { PlayerBar } from './player/PlayerBar'
import shineLogoUrl from '../../SHiNe.png'

type Theme = 'light' | 'dark' | 'black'
type NowPlayingState = 'closed' | 'opening' | 'open' | 'closing'

const navItems = desktopNavigation

export default function App() {
  const [section, setSection] = useState<Section>(() => sectionFromHash(location.hash))
  const [library, setLibrary] = useState<Track[]>([])
  const [libraryTotal, setLibraryTotal] = useState(0)
  const [libraryRevision, setLibraryRevision] = useState(0)
  const [favorites, setFavorites] = useState<Track[]>([])
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const [playlists, setPlaylists] = useState<PlaylistSummary[]>([])
  const [selectedPlaylist, setSelectedPlaylist] = useState<PlaylistDetail | null>(null)
  const [rooms, setRooms] = useState<RoomSummary[]>([])
  const [sources, setSources] = useState<SourceConfig[]>([])
  const [downloads, setDownloads] = useState<DownloadJob[]>([])
  const [libraries, setLibraries] = useState<MusicLibrary[]>([])
  const [searchResults, setSearchResults] = useState<Track[]>([])
  const [query, setQuery] = useState('')
  const [searchPresentationKey, setSearchPresentationKey] = useState(0)
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState<string | null>(null)
  const [nowPlayingState, setNowPlayingState] = useState<NowPlayingState>('closed')
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem('shine-theme') as Theme) || 'dark')
  const [queueOpen, setQueueOpen] = useState(true)
  const [queueTab, setQueueTab] = useState<'queue' | 'lyrics' | 'similar'>('queue')
  const [similarByTrack, setSimilarByTrack] = useState<Record<string, SimilarTracksResponse>>({})
  const [similarLoadingId, setSimilarLoadingId] = useState<string | null>(null)
  const [pullDistance, setPullDistance] = useState(0)
  const [pullRefreshing, setPullRefreshing] = useState(false)
  const pullStart = useRef<number | null>(null)
  const sessionRecent = useRef<string[]>([])
  const autoPrefetched = useRef<string | null>(null)
  const roomAutoPrefetched = useRef<string | null>(null)
  const analysisPollTimers = useRef(new Map<string, number>())
  const similarRequests = useRef(new Map<string, AbortController>())
  const roomIdRef = useRef<string | null>(null)
  const player = usePlayer()
  const activeTrackId = useRef<string | null>(player.current?.id ?? null)
  activeTrackId.current = player.current?.id ?? null
  const allTracks = useMemo(() => [...library, ...searchResults, ...favorites, ...history.map((entry) => entry.track), ...Object.values(similarByTrack).flatMap((response) => [response.seed, ...response.items.map((item) => item.track)])].filter((track, index, array) => array.findIndex((item) => item.id === track.id) === index), [favorites, history, library, searchResults, similarByTrack])
  const room = useRoomSync(player, allTracks)
  roomIdRef.current = room.roomId
  const activeDownloadCount = downloads.filter((job) => job.status === 'queued' || job.status === 'downloading').length

  const refresh = useCallback(async () => {
    try {
      const [libraryPage, favoriteTracks, historyEntries, playlistItems, roomItems, sourceItems, downloadItems, libraryItems] = await Promise.all([
        api.library(), api.favorites(), api.history(), api.playlists(), api.rooms(), api.sources(), api.downloads(), api.libraries(),
      ])
      setLibrary(libraryPage.items)
      setLibraryTotal(libraryPage.total)
      setLibraryRevision(libraryPage.revision)
      setFavorites(favoriteTracks)
      setHistory(historyEntries)
      setPlaylists(playlistItems)
      setRooms(roomItems)
      setSources(sourceItems)
      setDownloads(downloadItems)
      setLibraries(libraryItems)
    } catch (error) {
      setNotice(readError(error))
    } finally {
      setLoading(false)
    }
  }, [])

  const refreshDownloads = useCallback(async () => {
    const downloadItems = await api.downloads()
    setDownloads(downloadItems)
  }, [])

  const rememberLibraryTracks = useCallback((tracks: Track[]) => setLibrary((current) => mergeTrackPage(current, tracks)), [])
  const updateKnownTrack = useCallback((track: Track) => {
    setLibrary((current) => current.some((item) => item.id === track.id) ? current.map((item) => item.id === track.id ? track : item) : current)
    setSearchResults((current) => current.map((item) => item.id === track.id ? track : item))
    setFavorites((current) => current.map((item) => item.id === track.id ? track : item))
    setHistory((current) => current.map((entry) => entry.track.id === track.id ? { ...entry, track } : entry))
    setSelectedPlaylist((current) => current ? { ...current, tracks: current.tracks.map((item) => item.id === track.id ? track : item) } : current)
    player.hydrateTracks([track])
  }, [player.hydrateTracks])

  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refreshDownloads().catch(() => undefined)
    }, activeDownloadCount ? 1_000 : 5_000)
    return () => window.clearInterval(timer)
  }, [activeDownloadCount, refreshDownloads])
  useEffect(() => { player.hydrateTracks(allTracks) }, [allTracks, player.hydrateTracks])
  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('shine-theme', theme)
  }, [theme])
  useEffect(() => {
    const onHash = () => setSection(sectionFromHash(location.hash))
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  const navigate = (next: Section) => {
    if (next === section) {
      document.getElementById('main-content')?.scrollTo({ top: 0, behavior: 'smooth' })
      window.scrollTo({ top: 0, behavior: 'smooth' })
      return
    }
    location.hash = next
    setSection(next)
  }
  const finishPullRefresh = () => {
    pullStart.current = null
    if (pullDistance < 58 || pullRefreshing) { setPullDistance(0); return }
    setPullRefreshing(true)
    setPullDistance(48)
    void refresh().finally(() => { setPullRefreshing(false); setPullDistance(0) })
  }

  const openNowPlaying = () => {
    if (!player.current) return
    setNowPlayingState('opening')
  }

  const closeNowPlaying = () => setNowPlayingState('closing')

  const rememberPlayed = useCallback((id: string) => {
    sessionRecent.current = recentTrackIds(sessionRecent.current, id)
  }, [])

  const loadSimilar = useCallback(async (track: Track, force = false) => {
    if (!force && similarByTrack[track.id]) return similarByTrack[track.id]
    similarRequests.current.get(track.id)?.abort()
    const controller = new AbortController()
    const requestedRoomId = roomIdRef.current
    const requestedMode = requestedRoomId ? 'room' : 'independent'
    similarRequests.current.set(track.id, controller)
    setSimilarLoadingId(track.id)
    try {
      const recent = recentTrackIds([
        ...history.map((entry) => entry.track.id).reverse(),
        ...sessionRecent.current,
      ], track.id)
      const response = await api.similar(track.id, 24, recent, controller.signal)
      const currentMode = roomIdRef.current ? 'room' : 'independent'
      if (controller.signal.aborted || currentMode !== requestedMode || roomIdRef.current !== requestedRoomId) return null
      setSimilarByTrack((current) => ({ ...current, [track.id]: response }))
      return response
    } catch (error) {
      if (!controller.signal.aborted && force && roomIdRef.current === requestedRoomId) setNotice(readError(error))
      return null
    } finally {
      if (similarRequests.current.get(track.id) === controller) {
        similarRequests.current.delete(track.id)
        setSimilarLoadingId((current) => current === track.id ? null : current)
      }
    }
  }, [history, similarByTrack])

  useEffect(() => {
    similarRequests.current.forEach((controller) => controller.abort())
    similarRequests.current.clear()
    setSimilarLoadingId(null)
  }, [room.roomId])

  useEffect(() => () => {
    similarRequests.current.forEach((controller) => controller.abort())
    similarRequests.current.clear()
  }, [])

  const pollTrackAnalysis = useCallback((id: string) => {
    const previous = analysisPollTimers.current.get(id)
    if (previous) window.clearTimeout(previous)
    const poll = async () => {
      try {
        const [track] = await api.tracks([id])
        if (track) {
          updateKnownTrack(track)
          const terminal = track.analysis?.status === 'completed' || track.analysis?.status === 'failed' || track.analysis?.status === 'unavailable'
          if (terminal) {
            analysisPollTimers.current.delete(id)
            if (track.analysis?.status === 'completed') {
              setNotice(`《${track.title}》分析完成`)
              void loadSimilar(track, true)
            } else setNotice(track.analysis?.message || `《${track.title}》分析失败`)
            return
          }
        }
      } catch { /* transient NAS or network failure: keep polling */ }
      const timer = window.setTimeout(() => { void poll() }, 2500)
      analysisPollTimers.current.set(id, timer)
    }
    void poll()
  }, [loadSimilar, updateKnownTrack])

  useEffect(() => () => {
    analysisPollTimers.current.forEach((timer) => window.clearTimeout(timer))
    analysisPollTimers.current.clear()
  }, [])

  const play = (track: Track, queue: Track[]) => {
    rememberPlayed(track.id)
    if (room.roomId) {
      // Starting from a library, album, playlist, or recommendation intentionally
      // replaces the room queue with that source, matching independent playback.
      room.command({ queue: queue.map((item) => item.id), currentTrackId: track.id, positionMs: 0, playing: true })
    } else player.playTrack(track, queue)
  }

  const playQueuedTrack = (track: Track) => {
    rememberPlayed(track.id)
    if (room.roomId) {
      room.command({ currentTrackId: track.id, positionMs: 0, playing: true })
    } else player.playTrack(track, player.queue)
  }

  const togglePlayback = () => {
    if (room.roomId && player.current) {
      room.command({ currentTrackId: player.current.id, positionMs: Math.round(player.position * 1000), playing: !player.playing })
    } else player.toggle()
  }

  const skipPlayback = useCallback((delta: -1 | 1) => {
    if (!room.roomId) {
      if (delta === 1) player.next(); else player.previous()
      return
    }
    if (!player.current || room.queueIds.length === 0) return
    if (delta === -1 && player.position > 5) {
      room.command({ currentTrackId: player.current.id, positionMs: 0, playing: player.playing })
      return
    }
    const currentIndex = Math.max(0, room.queueIds.indexOf(player.current.id))
    const nextIndex = (currentIndex + delta + room.queueIds.length) % room.queueIds.length
    room.command({
      currentTrackId: room.queueIds[nextIndex],
      positionMs: 0,
      playing: true,
    })
  }, [player, room])

  const seekPlayback = useCallback((seconds: number) => {
    if (room.roomId && player.current) {
      room.command({ currentTrackId: player.current.id, positionMs: Math.round(seconds * 1000), playing: player.playing })
    } else player.seek(seconds)
  }, [player, room])

  useEffect(() => {
    if (room.roomId || !player.current || player.index < player.queue.length - 1) return
    if (!player.duration || player.duration - player.position > 20 || autoPrefetched.current === player.current.id) return
    autoPrefetched.current = player.current.id
    const seedId = player.current.id
    void loadSimilar(player.current).then((response) => {
      if (!response || roomIdRef.current || activeTrackId.current !== seedId) return
      const additions = mergeSimilarQueue(player.queue, response.items, recentTrackIds(sessionRecent.current, player.current?.id))
      player.appendTracks(additions)
    })
  }, [loadSimilar, player, room.roomId])

  useEffect(() => {
    if (!room.roomId || !player.current || room.queueIds.at(-1) !== player.current.id) return
    const duration = player.duration || player.current.durationMs / 1000
    if (!duration || duration - player.position > 20) return
    const requestKey = `${room.roomId}:${player.current.id}`
    if (roomAutoPrefetched.current === requestKey) return
    roomAutoPrefetched.current = requestKey
    void room.autofill(recentTrackIds(sessionRecent.current, player.current.id)).catch(() => {
      if (roomAutoPrefetched.current === requestKey) roomAutoPrefetched.current = null
    })
  }, [player.current?.id, player.duration, player.position, room.autofill, room.queueIds, room.roomId])

  useEffect(() => {
    const onEnded = () => {
      if (room.roomId) { skipPlayback(1); return }
      if (player.index < player.queue.length - 1) { player.next(); return }
      if (!player.current) return
      rememberPlayed(player.current.id)
      const seedId = player.current.id
      void loadSimilar(player.current, true).then((response) => {
        if (!response || roomIdRef.current || activeTrackId.current !== seedId) return
        const additions = mergeSimilarQueue(player.queue, response.items, recentTrackIds(sessionRecent.current, player.current?.id))
        player.continueWith(additions)
      })
    }
    player.audio.addEventListener('ended', onEnded)
    return () => player.audio.removeEventListener('ended', onEnded)
  }, [loadSimilar, player, rememberPlayed, room.roomId, skipPlayback])

  useEffect(() => {
    autoPrefetched.current = null
    if (!room.roomId && player.current) rememberPlayed(player.current.id)
    if (player.current?.analysis?.status === 'completed') void loadSimilar(player.current)
  }, [player.current?.id, room.roomId])

  const toggleFavorite = async (track: Track) => {
    const favorite = !favorites.some((item) => item.id === track.id)
    await api.setFavorite(track.id, favorite)
    await refresh()
  }

  const enqueueDownload = useCallback(async (track: Track) => {
    const job = await api.download(track)
    setDownloads((current) => [job, ...current.filter((item) => item.id !== job.id)])
    return job
  }, [])

  const analyzeTrack = async (original: Track) => {
    if (isTemporaryOnlineTrack(original)) {
      setNotice('在线临时歌曲需先下载入 NAS 曲库，再进行音乐画像分析')
      return
    }
    const queued: Track = { ...original, analysis: { ...original.analysis, status: 'queued', progress: .05, message: '等待分析' } }
    updateKnownTrack(queued)
    try {
      await api.analyze([original.id], false)
      setNotice(`《${original.title}》已加入分析队列`)
      pollTrackAnalysis(original.id)
    } catch (error) {
      updateKnownTrack(original)
      setNotice(readError(error))
    }
  }

  const analyzeCurrent = async () => {
    if (player.current) await analyzeTrack(player.current)
  }

  const queueNext = (track: Track) => {
    if (room.roomId) {
      const nextQueue = insertRoomTrackNext(room.queueIds, player.current?.id ?? null, track.id)
      if (nextQueue !== room.queueIds) room.command({ queue: nextQueue })
    } else player.insertNext(track)
    setNotice(`《${track.title}》将在下一首播放`)
  }

  const addToQueue = (track: Track) => {
    if (room.roomId) {
      const nextQueue = appendRoomTrack(room.queueIds, track.id)
      if (nextQueue !== room.queueIds) room.command({ queue: nextQueue })
      setNotice(nextQueue === room.queueIds ? `《${track.title}》已在队列中` : `《${track.title}》已加入队列`)
      return
    }
    const nextQueue = appendTrackOnce(player.queue, track)
    player.appendTracks([track])
    setNotice(nextQueue === player.queue ? `《${track.title}》已在队列中` : `《${track.title}》已加入队列`)
  }

  const addToPlaylist = async (track: Track, playlist: PlaylistSummary) => {
    try {
      const detail = await api.playlist(playlist.id)
      if (detail.tracks.some((item) => item.id === track.id)) {
        setNotice(`《${track.title}》已在“${playlist.name}”中`)
        return
      }
      await api.updatePlaylist(detail.id, [...detail.tracks.map((item) => item.id), track.id], detail.version)
      await refresh()
      setNotice(`《${track.title}》已添加到“${playlist.name}”`)
    } catch (error) { setNotice(readError(error)) }
  }

  const openTrackFacet = async (track: Track, value: string, label: string) => {
    if (!value.trim()) return
    try {
      const online = isTemporaryOnlineTrack(track)
      localStorage.setItem('shine-search-mode', online ? 'domesticTracks' : 'library')
      const items = online ? (await api.search(value, track.source || 'all')).items : (await api.library(0, value)).items
      setQuery(value)
      setSearchResults(items)
      setSearchPresentationKey((current) => current + 1)
      navigate('search')
      setNotice(`正在查看${label}“${value}”`)
    } catch (error) { setNotice(readError(error)) }
  }

  const shareTrack = async (track: Track) => {
    const text = `${track.title} — ${track.artist} · SHiNe MUSIC`
    try {
      if (navigator.share) await navigator.share({ title: track.title, text })
      else {
        await copyText(text)
        setNotice('歌曲信息已复制，可粘贴分享')
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
      try { await copyText(text); setNotice('歌曲信息已复制，可粘贴分享') } catch { setNotice('当前浏览器无法分享这首歌') }
    }
  }

  const trackMenuActions: TrackMenuActionFactory = (track) => {
    const temporaryOnline = isTemporaryOnlineTrack(track)
    const favorite = favorites.some((item) => item.id === track.id)
    const localOnlyReason = '请先下载入 NAS 曲库'
    return [
      { id: 'play-next', label: '下一首播放', icon: 'next', onSelect: () => queueNext(track) },
      { id: 'add-queue', label: '加入播放队列', icon: 'queue', onSelect: () => addToQueue(track) },
      { id: 'favorite', label: favorite ? '取消收藏' : '收藏', icon: 'heart', onSelect: async () => { try { await toggleFavorite(track) } catch (error) { setNotice(readError(error)) } } },
      {
        id: 'add-playlist', label: '添加到播放列表', icon: 'playlist', disabled: !playlists.length,
        description: !playlists.length ? '请先创建一张共享歌单' : undefined,
        children: playlists.map((playlist) => ({ id: `playlist-${playlist.id}`, label: playlist.name, icon: 'playlist', onSelect: () => addToPlaylist(track, playlist) })),
      },
      { id: 'share', label: '分享', icon: 'share', onSelect: () => shareTrack(track) },
      { id: 'artist', label: '查看歌手', icon: 'artist', disabled: !track.artist.trim(), onSelect: () => openTrackFacet(track, track.artist, '歌手') },
      { id: 'album', label: '查看专辑', icon: 'album', disabled: !track.album.trim(), onSelect: () => openTrackFacet(track, track.album, '专辑') },
      { id: 'analyze', label: '分析音乐画像', icon: 'radar', disabled: temporaryOnline, description: temporaryOnline ? localOnlyReason : undefined, onSelect: () => analyzeTrack(track) },
    ]
  }

  const moveQueueItem = (index: number, delta: -1 | 1) => {
    if (!room.roomId) {
      const target = index + delta
      if (target < 0 || target >= player.queue.length) return
      player.moveQueueItem(index, target)
      return
    }
    const trackId = player.queue[index]?.id
    if (!trackId) return
    const nextQueue = moveRoomTrack(room.queueIds, trackId, delta)
    if (nextQueue !== room.queueIds) room.command({ queue: nextQueue })
  }

  const removeQueueItem = (index: number) => {
    if (!room.roomId) {
      player.removeQueueItem(index)
      return
    }
    const trackId = player.queue[index]?.id
    if (!trackId) return
    const removedCurrent = trackId === player.current?.id
    const next = removeRoomTrack(room.queueIds, player.current?.id ?? null, trackId)
    if (next.queue === room.queueIds) return
    room.command({
      queue: next.queue,
      currentTrackId: next.currentTrackId,
      positionMs: removedCurrent ? 0 : Math.round(player.position * 1000),
      playing: Boolean(next.currentTrackId && player.playing),
    })
  }

  const title = navItems.find((item) => item.id === section)?.label ?? ({ history: '历史记录', stats: '收听统计', settings: '设置' } as Partial<Record<Section, string>>)[section] ?? 'SHiNe MUSIC'
  const currentSimilar = player.current ? similarByTrack[player.current.id] ?? null : null

  return (
    <TrackActionsProvider value={trackMenuActions}>
    <div className={`app-shell ${queueOpen ? '' : 'queue-closed'}`}>
      <DesktopShell active={section} onNavigate={navigate} activeDownloads={activeDownloadCount} queueOpen={queueOpen} queue={<>
        <div className="panel-tabs" role="tablist" aria-label="播放侧栏"><button role="tab" aria-selected={queueTab === 'queue'} className={queueTab === 'queue' ? 'active' : ''} onClick={() => setQueueTab('queue')}>队列 <span>{room.roomId ? room.queueIds.length : player.queue.length}</span></button><button role="tab" aria-selected={queueTab === 'lyrics'} className={queueTab === 'lyrics' ? 'active' : ''} onClick={() => setQueueTab('lyrics')}>歌词</button><button role="tab" aria-selected={queueTab === 'similar'} className={queueTab === 'similar' ? 'active' : ''} onClick={() => { setQueueTab('similar'); if (player.current) void loadSimilar(player.current) }}>相似</button></div>
        {queueTab === 'queue' ? <QueueEditor compact tracks={player.queue} currentIndex={player.index} playing={player.playing} onPlay={playQueuedTrack} onMove={moveQueueItem} onRemove={removeQueueItem} /> : queueTab === 'lyrics' ? <LyricsPanel track={player.current} positionSeconds={player.position} onSeek={seekPlayback} /> : currentSimilar?.items.length ? <div className="panel-similar-list">{currentSimilar.items.map((item) => <button key={item.track.id} onClick={() => play(item.track, currentSimilar.items.map((match) => match.track))}><RadarChart analysis={item.track.analysis} compact /><span><strong>{item.track.title}</strong><small>{item.track.artist}</small><AnalysisChips track={item.track} compact /></span><em>{item.similarityPercent}%</em></button>)}</div> : <EmptyState compact title={similarLoadingId ? '正在计算相似音乐' : '还没有相似推荐'} body="完成曲目分析后，会按节拍、调性和七维听感延续播放。" />}
      </>} />

      <main className="main-content" id="main-content" onPointerDown={(event) => { if (section === 'home' && event.pointerType === 'touch' && window.scrollY <= 0 && event.currentTarget.scrollTop <= 0) pullStart.current = event.clientY }} onPointerMove={(event) => { if (pullStart.current === null) return; const distance = event.clientY - pullStart.current; if (distance > 0) setPullDistance(Math.min(82, distance * .42)) }} onPointerUp={finishPullRefresh} onPointerCancel={() => { pullStart.current = null; setPullDistance(0) }}>
        {section === 'home' && <div className={`pull-refresh ${pullRefreshing ? 'refreshing' : ''}`} style={{ transform: `translate(-50%, ${pullDistance - 48}px)`, opacity: pullDistance ? 1 : 0 }} role="status" aria-live="polite"><Icon name="refresh" /><span>{pullRefreshing ? '正在刷新' : pullDistance >= 58 ? '松开刷新' : '下拉刷新'}</span></div>}
        <header className="topbar">
          <h1>{title}</h1>
          <div className="topbar-actions">
            <button className={`connection ${room.status}`} title={`Sendspin 自动时钟校准：NAS 时钟偏移 ${Math.round(room.serverOffset)}ms`} onClick={() => navigate('rooms')}><span className="status-dot" />{roomConnectionLabel(room)}</button>
            <button className="topbar-icon download-shortcut" onClick={() => navigate('downloads')} aria-label={activeDownloadCount ? `打开下载任务，${activeDownloadCount} 个进行中` : '打开下载任务'}><Icon name="download" />{activeDownloadCount > 0 && <span>{activeDownloadCount > 99 ? '99+' : activeDownloadCount}</span>}</button>
            <button className="topbar-icon original-top-action" onClick={() => navigate('history')} aria-label="打开播放历史"><Icon name="history" /></button>
            <button className="topbar-icon original-top-action" onClick={() => navigate('stats')} aria-label="打开收听统计"><Icon name="stats" /></button>
            <button className="topbar-icon" onClick={() => navigate('settings')} aria-label="打开设置"><Icon name="settings" /></button>
            <button className="queue-toggle secondary-button" onClick={() => setQueueOpen((value) => !value)} aria-expanded={queueOpen} aria-controls="play-queue">{queueOpen ? '收起队列' : '展开队列'}</button>
          </div>
        </header>
        {notice && <div className="notice"><span role="status">{notice}</span><span className="notice-actions">{notice.includes('NAS 下载队列') && <button className="notice-link" onClick={() => { setNotice(null); navigate('downloads') }}>查看下载</button>}<button onClick={() => setNotice(null)} aria-label="关闭提示">×</button></span></div>}
        {loading ? <LoadingRows /> : (
          <div className="page-content">
            {section === 'home' && <HomePage history={history} favorites={favorites} library={library} libraryTotal={libraryTotal} playlists={playlists} similar={currentSimilar} current={player.current} playing={player.playing} onPlay={play} onNavigate={navigate} onLoadSimilar={() => { if (player.current) void loadSimilar(player.current) }} />}
            {section === 'search' && <SearchPage key={searchPresentationKey} query={query} setQuery={setQuery} results={searchResults} setResults={setSearchResults} onPlay={play} onFavorite={toggleFavorite} onDownload={enqueueDownload} favorites={favorites} onNotice={setNotice} />}
            {section === 'library' && <LibraryHub playlists={playlists} favorites={favorites} downloads={downloads} selected={selectedPlaylist} setSelected={setSelectedPlaylist} library={library} onPlay={play} onFavorite={toggleFavorite} onNavigate={navigate} onRefresh={refresh} onNotice={setNotice} />}
            {section === 'local' && <LibraryPage libraries={libraries} initialTracks={library} initialTotal={libraryTotal} initialRevision={libraryRevision} favorites={favorites} onPlay={play} onFavorite={toggleFavorite} onDiscover={rememberLibraryTracks} onPollAnalysis={pollTrackAnalysis} onRefresh={refresh} onNotice={setNotice} />}
            {section === 'downloads' && <DownloadQueuePage jobs={downloads} onRefresh={refreshDownloads} onRetry={async (id) => { try { await api.retryDownload(id); await refreshDownloads(); setNotice('下载任务已重新加入队列') } catch (error) { setNotice(readError(error)) } }} />}
            {section === 'rooms' && <RoomsPage rooms={rooms} room={room} onRefresh={refresh} onNotice={setNotice} />}
            {section === 'history' && <HistoryPage history={history} favorites={favorites} onPlay={play} onFavorite={toggleFavorite} />}
            {section === 'stats' && <StatsPage history={history} library={library} favorites={favorites} />}
            {section === 'settings' && <SettingsPage libraries={libraries} sources={sources} downloads={downloads} theme={theme} onTheme={setTheme} onNavigate={navigate} onRefresh={refresh} onNotice={setNotice} />}
          </div>
        )}
      </main>

      <PlayerBar player={player} room={room} favorite={Boolean(player.current && favorites.some((track) => track.id === player.current?.id))} onFavorite={() => { if (player.current) void toggleFavorite(player.current) }} onToggle={togglePlayback} onPrevious={() => skipPlayback(-1)} onNext={() => skipPlayback(1)} onSeek={seekPlayback} onOpen={openNowPlaying} onLyrics={() => { setQueueOpen(true); setQueueTab('lyrics') }} />
      <MobileShell active={section} onNavigate={navigate} />
      {nowPlayingState !== 'closed' && <NowPlaying presentation={nowPlayingState} player={player} favorite={Boolean(player.current && favorites.some((track) => track.id === player.current?.id))} similar={currentSimilar} similarLoading={similarLoadingId === player.current?.id} onFavorite={() => { if (player.current) void toggleFavorite(player.current) }} onToggle={togglePlayback} onPrevious={() => skipPlayback(-1)} onNext={() => skipPlayback(1)} onSeek={seekPlayback} onClose={closeNowPlaying} onClosed={() => setNowPlayingState('closed')} onLoadSimilar={() => { if (player.current) void loadSimilar(player.current) }} onAnalyze={() => void analyzeCurrent()} onPlaySimilar={play} onMoveQueue={moveQueueItem} onRemoveQueue={removeQueueItem} />}
    </div>
    </TrackActionsProvider>
  )
}

function HomePage({ history, favorites, library, libraryTotal, playlists, current, playing, similar, onPlay, onNavigate, onLoadSimilar }: { history: HistoryEntry[]; favorites: Track[]; library: Track[]; libraryTotal: number; playlists: PlaylistSummary[]; current: Track | null; playing: boolean; similar: SimilarTracksResponse | null; onPlay: (track: Track, queue: Track[]) => void; onNavigate: (section: Section) => void; onLoadSimilar: () => void }) {
  const recent = history.slice(0, 8).map((entry) => entry.track)
  const recommendations = similar?.items.map((item) => item.track) ?? library.filter((track) => track.analysis?.status === 'completed').slice(0, 10)
  const recommendationBadges = new Map(similar?.items.map((item) => [item.track.id, `${item.similarityPercent}% 相似`]) ?? [])
  const analyzed = library.filter((track) => track.analysis?.status === 'completed')
  const quickPicks = [...recent, ...favorites, ...library].filter((track, index, items) => items.findIndex((item) => item.id === track.id) === index).slice(0, 10)
  const daily = (analyzed.length ? analyzed : library).slice(0, 10)
  const forgotten = favorites.length > 4 ? [...favorites].reverse().slice(0, 8) : []
  const pageSize = useSpeedDialPageSize()
  const [dialPage, setDialPage] = useState(0)
  const dialViewport = useRef<HTMLDivElement>(null)
  const randomize = () => {
    if (!library.length) return
    const shuffled = [...library]
    for (let index = shuffled.length - 1; index > 0; index--) {
      const target = Math.floor(Math.random() * (index + 1))
      ;[shuffled[index], shuffled[target]] = [shuffled[target], shuffled[index]]
    }
    onPlay(shuffled[0], shuffled)
  }
  const dialBase = [
    { id: 'nas', label: 'NAS 曲库', detail: `${libraryTotal} 首音乐`, className: 'four-covers', action: () => onNavigate('local'), art: library.slice(0, 4).map((track) => <AlbumArt key={track.id} title={track.title} artworkUrl={track.artworkUrl} small />) },
    { id: 'liked', label: '已点赞', detail: `${favorites.length} 首音乐`, className: 'liked', action: () => onNavigate('library'), art: <Icon name="heart" /> },
    { id: 'playlists', label: '播放列表', detail: `${playlists.length} 个共享歌单`, className: 'playlists', action: () => onNavigate('library'), art: <Icon name="playlist" /> },
    { id: 'room', label: '同步房间', detail: '多台音响一起播放', className: 'room', action: () => onNavigate('rooms'), art: <Icon name="room" /> },
    { id: 'continue', label: '继续聆听', detail: recent[0]?.title ?? '最近播放', className: 'continue', action: () => recent[0] ? onPlay(recent[0], recent) : onNavigate('local'), art: recent[0] ? <AlbumArt title={recent[0].title} artworkUrl={recent[0].artworkUrl} small /> : <Icon name="play" /> },
    { id: 'analyzed', label: '音乐画像', detail: `${analyzed.length} 首已分析`, className: 'analyzed', action: () => onNavigate('search'), art: <Icon name="radar" /> },
    { id: 'daily', label: '每日发现', detail: '从熟悉的听感开始', className: 'daily', action: () => daily[0] ? onPlay(daily[0], daily) : onNavigate('local'), art: <Icon name="sparkles" /> },
  ]
  const randomDial = { id: 'random', label: '随机播放', detail: library.length ? `打乱 ${libraryTotal} 首` : '曲库扫描后可用', className: 'random', action: randomize, art: <Icon name="sparkles" /> }
  const firstPageEnd = Math.max(0, pageSize - 1)
  const dialItems = [...dialBase.slice(0, firstPageEnd), randomDial, ...dialBase.slice(firstPageEnd)]
  const pageCount = Math.max(1, Math.ceil(dialItems.length / pageSize))
  const safePage = Math.min(dialPage, pageCount - 1)
  const dialPages = Array.from({ length: pageCount }, (_, page) => dialItems.slice(page * pageSize, page * pageSize + pageSize))
  useEffect(() => { if (dialPage >= pageCount) setDialPage(pageCount - 1) }, [dialPage, pageCount])
  const goToDialPage = (page: number) => {
    const viewport = dialViewport.current
    const nextPage = Math.min(pageCount - 1, Math.max(0, page))
    setDialPage(nextPage)
    if (viewport) viewport.scrollTo({ left: pageOffset(nextPage, viewport.clientWidth, pageCount), behavior: 'smooth' })
  }
  return <>
    <section className="content-rail speed-dial"><div className="section-heading"><h2>快速访问</h2></div><div ref={dialViewport} className="speed-dial-viewport" aria-label={`快速访问，第 ${safePage + 1} 页，共 ${pageCount} 页`} onScroll={(event) => setDialPage(pageFromScroll(event.currentTarget.scrollLeft, event.currentTarget.clientWidth, pageCount))}>{dialPages.map((items, page) => <div className="quick-grid speed-dial-page" style={{ gridTemplateColumns: `repeat(${pageSize}, minmax(0, 1fr))` }} key={page} aria-label={`快速访问第 ${page + 1} 页`}>{items.map((item) => <button key={item.id} onClick={item.action} disabled={item.id === 'random' && !library.length}><span className={`quick-art ${item.className}`}>{item.art}</span><strong>{item.label}</strong><small>{item.detail}</small></button>)}</div>)}</div><div className="speed-dial-pages" aria-label="快速访问页码">{Array.from({ length: pageCount }, (_, index) => <button key={index} className={safePage === index ? 'active' : ''} aria-label={`第 ${index + 1} 页`} aria-current={safePage === index ? 'page' : undefined} onClick={() => goToDialPage(index)} />)}</div></section>
    <HomeSongRail title="Quick Picks" tracks={quickPicks} current={current} playing={playing} onPlay={onPlay} />
    <AlbumRow title="继续聆听" tracks={(recent.length ? recent : favorites).slice(0, 8)} onPlay={onPlay} />
    <AlbumRow title="每日发现" tracks={daily} onPlay={onPlay} />
    {playlists.length > 0 && <section className="content-rail"><div className="section-heading"><div><h2>账号歌单</h2><p>局域网内共享，任何设备都能继续整理</p></div><button className="text-button" onClick={() => onNavigate('library')}>查看全部</button></div><div className="home-playlist-row">{playlists.slice(0, 8).map((playlist) => <button key={playlist.id} onClick={() => onNavigate('library')}><span className="quick-art playlists"><Icon name="playlist" /></span><strong>{playlist.name}</strong><small>{playlist.trackCount} 首</small></button>)}</div></section>}
    <section className="content-rail"><div className="section-heading"><div><h2>相似推荐</h2><p>{current ? `延续《${current.title}》的节拍、调性与听感` : '播放一首已分析音乐后，从这里自然续播'}</p></div>{current && <button className="text-button" onClick={onLoadSimilar}><Icon name="sparkles" />重新计算</button>}</div><AlbumRow title="" tracks={recommendations} badges={recommendationBadges} onPlay={onPlay} />{!recommendations.length && <EmptyState compact title="等待音乐画像" body="NAS 完成曲目分析后，相似推荐会出现在这里。" />}</section>
    <AlbumRow title="情绪与风格" tracks={analyzed.slice(0, 10)} onPlay={onPlay} />
    <AlbumRow title="遗忘收藏" tracks={forgotten} onPlay={onPlay} />
  </>
}

function HomeSongRail({ title, tracks, current, playing, onPlay }: { title: string; tracks: Track[]; current: Track | null; playing: boolean; onPlay: (track: Track, queue: Track[]) => void }) {
  if (!tracks.length) return null
  return <section className="content-rail home-song-section"><div className="section-heading"><h2>{title}</h2><button className="text-button" onClick={() => onPlay(tracks[0], tracks)}><Icon name="play" />全部播放</button></div><div className="home-song-rail" aria-label={title}>{tracks.map((track) => { const active = current?.id === track.id; return <button key={track.id} className={active ? 'active' : ''} onClick={() => onPlay(track, tracks)}><span className="home-song-art"><AlbumArt title={track.title} artworkUrl={track.artworkUrl} small />{active && <i><Icon name={playing ? 'pause' : 'play'} /></i>}</span><span><strong>{track.title}</strong><small>{track.artist}{track.album ? ` · ${track.album}` : ''}</small></span><b aria-hidden="true">•••</b></button> })}</div></section>
}

function useSpeedDialPageSize() {
  const measure = () => window.innerWidth < 768 ? 3 : window.innerWidth < 1200 ? 6 : 8
  const [size, setSize] = useState(measure)
  useEffect(() => {
    const resize = () => setSize(measure())
    window.addEventListener('resize', resize)
    return () => window.removeEventListener('resize', resize)
  }, [])
  return size
}

function SearchPage({ query, setQuery, results, setResults, onPlay, onFavorite, onDownload, favorites, onNotice }: {
  query: string; setQuery: (value: string) => void; results: Track[]; setResults: (value: Track[]) => void
  onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void; onDownload: (track: Track) => Promise<DownloadJob>; favorites: Track[]; onNotice: (value: string) => void
}) {
  const [searching, setSearching] = useState(false)
  const [source, setSource] = useState(() => localStorage.getItem('shine-search-provider') || 'all')
  const [mode, setMode] = useState<'domesticTracks' | 'domesticPlaylists' | 'library' | 'advanced'>(() => {
    const stored = localStorage.getItem('shine-search-mode')
    return stored === 'domesticPlaylists' || stored === 'library' || stored === 'advanced' ? stored : 'domesticTracks'
  })
  const [searchHistory, setSearchHistory] = useState(readSearchHistory)
  const [hasSearched, setHasSearched] = useState(false)
  const [pendingTrack, setPendingTrack] = useState<{ track: Track; queue: Track[] } | null>(null)
  useEffect(() => { localStorage.setItem('shine-search-mode', mode) }, [mode])
  useEffect(() => { localStorage.setItem('shine-search-provider', source) }, [source])
  const rememberSearch = (term: string) => setSearchHistory((current) => {
    const next = [term, ...current.filter((item) => item !== term)].slice(0, 20)
    localStorage.setItem('shine-search-history', JSON.stringify(next))
    return next
  })
  const search = async (event: FormEvent) => {
    event.preventDefault()
    const term = query.trim()
    if (!term || mode === 'advanced') return
    if (mode === 'domesticPlaylists') {
      setResults([])
      setHasSearched(true)
      rememberSearch(term)
      return
    }
    setSearching(true)
    try {
      const items = mode === 'library' ? (await api.library(0, term)).items : (await api.search(term, source)).items
      setResults(items)
      setHasSearched(true)
      rememberSearch(term)
    } catch (error) { onNotice(readError(error)) } finally { setSearching(false) }
  }
  const download = async (track: Track) => {
    try { await onDownload(track); onNotice(`《${track.title}》已加入 NAS 下载队列`) } catch (error) { onNotice(readError(error)) }
  }
  return <div className="search-page">
    <form className="search-box search-box-original" onSubmit={search} role="search"><button type="button" className="search-back" onClick={() => window.history.back()} aria-label="返回"><Icon name="back" /></button><Icon name="search" /><input value={query} onChange={(event) => { setQuery(event.target.value); if (!event.target.value) setHasSearched(false) }} placeholder={mode === 'library' ? '搜索曲库、歌手、专辑或文件名' : '搜索国内音乐…'} aria-label={mode === 'library' ? '曲库搜索' : '在线搜索'} />{query && <button type="button" className="search-clear" onClick={() => { setQuery(''); setResults([]); setHasSearched(false) }} aria-label="清除搜索"><Icon name="close" /></button>}<button className="primary-button" disabled={searching || mode === 'advanced'}>{searching ? '搜索中…' : '搜索'}</button></form>
    <div className="search-source-switcher" aria-label="搜索来源"><div className="search-source-pair" role="tablist" aria-label="国内来源"><button role="tab" aria-selected={mode === 'domesticTracks'} className={mode === 'domesticTracks' ? 'active' : ''} onClick={() => setMode('domesticTracks')}><Icon name="search" /><span>国内歌曲</span></button><button role="tab" aria-selected={mode === 'domesticPlaylists'} className={mode === 'domesticPlaylists' ? 'active' : ''} onClick={() => setMode('domesticPlaylists')}><Icon name="playlist" /><span>国内歌单</span></button></div><div className="search-source-pair" role="tablist" aria-label="本地与分析"><button role="tab" aria-selected={mode === 'library'} className={mode === 'library' ? 'active' : ''} onClick={() => setMode('library')}><Icon name="library" /><span>曲库</span></button><button role="tab" aria-label="高级听感" aria-selected={mode === 'advanced'} className={mode === 'advanced' ? 'active' : ''} onClick={() => setMode('advanced')}><Icon name="radar" /><span>高级</span></button></div></div>
    {mode === 'advanced' ? <AdvancedSearchPanel favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} onNotice={onNotice} /> : <>
      {(mode === 'domesticTracks' || mode === 'domesticPlaylists') && <div className="source-chips" role="group" aria-label="国内音乐音源">{[['all', '聚合'], ['netease', '网易云'], ['qq', 'QQ'], ['kugou', '酷狗'], ['kuwo', '酷我'], ['migu', '咪咕']].map(([value, label]) => <button key={value} className={source === value ? 'active' : ''} aria-pressed={source === value} onClick={() => setSource(value)}>{label}</button>)}</div>}
      {!query.trim() && searchHistory.length > 0 && <section className="search-history"><div className="section-heading"><h2>搜索历史</h2><button className="text-button" onClick={() => { localStorage.removeItem('shine-search-history'); setSearchHistory([]) }}>清空</button></div><div className="history-chips">{searchHistory.map((item) => <button key={item} onClick={() => { setQuery(item); setHasSearched(true) }}>{item}</button>)}</div></section>}
      {mode === 'domesticPlaylists'
        ? <OnlinePlaylistBrowser query={hasSearched ? query : ''} source={source} onPlay={onPlay} onDownload={onDownload} onNotice={onNotice} />
        : results.length
          ? <TrackList tracks={results} favorites={favorites} onPlay={mode === 'domesticTracks' ? (track, queue) => setPendingTrack({ track, queue }) : onPlay} onFavorite={onFavorite} trailingAction={mode === 'library' ? undefined : (track) => <button className="icon-button" onClick={() => void download(track)} title="下载到 NAS" aria-label={`下载 ${track.title} 到 NAS`}><Icon name="download" /></button>} />
          : query.trim() && hasSearched
            ? <EmptyState title="没有找到匹配内容" body={mode === 'library' ? '试试歌手、专辑或文件名中的其他关键词。' : '可以切换国内歌曲或其他音源再试一次。'} />
            : !searchHistory.length && <EmptyState title="从这里开始搜索" body="选择国内歌曲、国内歌单、NAS 曲库或高级听感；搜索记录会保存在这台设备上。" />}
    </>}
    {pendingTrack && <OnlineConfirmationDialog kind="track" title={pendingTrack.track.title} subtitle={pendingTrack.track.artist} sourceLabel={sourceLabel(pendingTrack.track.source ?? source)} artworkUrl={pendingTrack.track.artworkUrl} onCancel={() => setPendingTrack(null)} onConfirm={() => { onPlay(pendingTrack.track, pendingTrack.queue); setPendingTrack(null) }} />}
  </div>
}

function readSearchHistory(): string[] {
  try {
    const value = JSON.parse(localStorage.getItem('shine-search-history') ?? '[]')
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string').slice(0, 20) : []
  } catch { return [] }
}

function LibraryHub({ playlists, favorites, downloads, selected, setSelected, library, onPlay, onFavorite, onNavigate, onRefresh, onNotice }: { playlists: PlaylistSummary[]; favorites: Track[]; downloads: DownloadJob[]; selected: PlaylistDetail | null; setSelected: (value: PlaylistDetail | null) => void; library: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void; onNavigate: (section: Section) => void; onRefresh: () => Promise<void>; onNotice: (value: string) => void }) {
  const [tab, setTab] = useState<'overview' | 'favorites'>('overview')
  const [filter, setFilter] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [name, setName] = useState('')
  if (selected) return <PlaylistsPage playlists={playlists} selected={selected} setSelected={setSelected} library={library} onPlay={onPlay} onRefresh={onRefresh} onNotice={onNotice} />
  const activeDownloads = downloads.filter((job) => job.status === 'queued' || job.status === 'downloading').length
  const completedDownloads = downloads.filter((job) => job.status === 'completed').length
  const visiblePlaylists = playlists.filter((playlist) => playlist.name.toLocaleLowerCase().includes(filter.trim().toLocaleLowerCase()))
  const openPlaylist = async (id: string) => { try { setSelected(await api.playlist(id)) } catch (error) { onNotice(readError(error)) } }
  const create = async (event: FormEvent) => {
    event.preventDefault()
    if (!name.trim()) return
    try { await api.createPlaylist(name); setName(''); setShowCreate(false); await onRefresh() } catch (error) { onNotice(readError(error)) }
  }
  if (tab === 'favorites') return <><button className="back-button" onClick={() => setTab('overview')}><Icon name="back" />返回媒体库</button><TrackSection title="已点赞" subtitle={`${favorites.length} 首 · 家庭共享`} tracks={favorites} favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} /></>
  return <>
    <div className="library-overview-toolbar"><label className="filter-field"><Icon name="search" /><input value={filter} onChange={(event) => setFilter(event.target.value)} placeholder="搜索播放列表" aria-label="搜索播放列表" /></label><span>{visiblePlaylists.length + 4} 个播放列表</span><button className="icon-button" aria-label="列表视图"><Icon name="playlist" /></button></div>
    <div className="library-overview-list">
      {!filter && <>
        <button className="library-overview-row" onClick={() => setTab('favorites')}><span className="library-row-art liked"><Icon name="heart" /></span><span><strong>已点赞</strong><small>{favorites.length} 首歌曲 · 家庭共享</small></span><b>›</b></button>
        <button className="library-overview-row" onClick={() => onNavigate('downloads')}><span className="library-row-art downloaded"><Icon name="download" />{activeDownloads > 0 && <i />}</span><span><strong>已下载</strong><small>{activeDownloads ? `${activeDownloads} 个任务进行中` : `${completedDownloads} 个任务已完成`}</small></span><b>›</b></button>
        <button className="library-overview-row" onClick={() => onNavigate('local')}><span className="library-row-art covers">{library.slice(0, 4).map((track) => <AlbumArt key={track.id} title={track.title} artworkUrl={track.artworkUrl} small />)}</span><span><strong>NAS 曲库</strong><small>{library.length} 首音乐</small></span><b>›</b></button>
        <button className="library-overview-row" disabled={!library.length} onClick={() => { const top = library.slice(0, 50); if (top[0]) onPlay(top[0], top) }}><span className="library-row-art top"><Icon name="stats" /></span><span><strong>我的最爱 50</strong><small>根据当前曲库快速播放</small></span><b>›</b></button>
      </>}
      {visiblePlaylists.map((playlist) => <button className="library-overview-row" key={playlist.id} onClick={() => void openPlaylist(playlist.id)}><span className="library-row-art playlist"><Icon name="playlist" /></span><span><strong>{playlist.name}</strong><small>{playlist.trackCount} 首歌曲 · 共享歌单</small></span><b>•••</b></button>)}
    </div>
    {!visiblePlaylists.length && filter && <EmptyState compact title="没有匹配的播放列表" body="换一个名称继续搜索。" />}
    {showCreate && <form className="inline-create library-inline-create" onSubmit={create}><input autoFocus value={name} onChange={(event) => setName(event.target.value)} placeholder="新歌单名称" aria-label="新歌单名称" /><button type="button" className="secondary-button" onClick={() => setShowCreate(false)}>取消</button><button className="primary-button">创建</button></form>}
    <button className="library-create-fab" onClick={() => setShowCreate(true)} aria-label="创建歌单">＋</button>
  </>
}

function LibraryPage({ libraries, initialTracks, initialTotal, initialRevision, favorites, onPlay, onFavorite, onDiscover, onPollAnalysis, onRefresh, onNotice }: { libraries: MusicLibrary[]; initialTracks: Track[]; initialTotal: number; initialRevision: number; favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void; onDiscover: (tracks: Track[]) => void; onPollAnalysis: (id: string) => void; onRefresh: () => Promise<void>; onNotice: (value: string) => void }) {
  const [view, setView] = useState<'tracks' | 'analysis'>('tracks')
  const [filter, setFilter] = useState('')
  const [sort, setSort] = useState<LibrarySort>('artist')
  const [sortDirection, setSortDirection] = useState<LibrarySortDirection>('asc')
  const [libraryId, setLibraryId] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [tracks, setTracks] = useState(() => initialTracks.slice(0, LIBRARY_PAGE_SIZE))
  const [total, setTotal] = useState(initialTotal)
  const [revision, setRevision] = useState(initialRevision)
  const [nextOffset, setNextOffset] = useState(Math.min(initialTracks.length, LIBRARY_PAGE_SIZE))
  const [loadingMore, setLoadingMore] = useState(false)
  const [analysisSummary, setAnalysisSummary] = useState<AnalysisSummary | null>(null)
  const [analysisBusy, setAnalysisBusy] = useState(false)
  const requestGeneration = useRef(0)
  const filterRef = useRef(filter)
  const sortRef = useRef(sort)
  const sortDirectionRef = useRef(sortDirection)
  const libraryRef = useRef(libraryId)
  filterRef.current = filter
  sortRef.current = sort
  sortDirectionRef.current = sortDirection
  libraryRef.current = libraryId
  useEffect(() => {
    let cancelled = false
    const generation = requestGeneration.current
    const timer = window.setTimeout(() => {
      void api.library(0, filter, sort, libraryId, sortDirection).then((page) => {
        if (cancelled || generation !== requestGeneration.current) return
        setTracks(page.items)
        setTotal(page.total)
        setRevision(page.revision)
        setNextOffset(page.offset + page.items.length)
        setSelected([])
        onDiscover(page.items)
      }).catch((error) => { if (!cancelled) onNotice(readError(error)) })
    }, 250)
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [filter, libraryId, onDiscover, onNotice, sort, sortDirection])
  const reloadCurrentView = useCallback(async () => {
    const generation = requestGeneration.current
    const page = await api.library(0, filterRef.current, sortRef.current, libraryRef.current, sortDirectionRef.current)
    if (generation !== requestGeneration.current) return
    setTracks(page.items)
    setTotal(page.total)
    setRevision(page.revision)
    setNextOffset(page.offset + page.items.length)
    setSelected([])
    onDiscover(page.items)
  }, [onDiscover])
  useEffect(() => {
    if (view !== 'analysis') return
    let active = true
    let lastSnapshot = ''
    const synchronize = async () => {
      try {
        const summary = await api.analysis()
        if (!active) return
        setAnalysisSummary(summary)
        const snapshot = `${summary.pending}:${summary.queued}:${summary.running}:${summary.completed}:${summary.failed}`
        if (snapshot !== lastSnapshot) {
          lastSnapshot = snapshot
          await reloadCurrentView()
        }
      } catch { /* analysis progress is advisory; the catalog stays usable during transient NAS errors */ }
    }
    void synchronize()
    const timer = window.setInterval(() => { void synchronize() }, 2500)
    return () => { active = false; window.clearInterval(timer) }
  }, [reloadCurrentView, view])
  const loadMore = async () => {
    if (loadingMore || nextOffset >= total) return
    const generation = requestGeneration.current
    setLoadingMore(true)
    try {
      const page = await api.library(nextOffset, filter, sort, libraryId, sortDirection)
      if (generation !== requestGeneration.current) return
      const knownIds = new Set(tracks.map((track) => track.id))
      if (page.revision !== revision || page.total !== total || page.items.some((track) => knownIds.has(track.id))) {
        const fresh = await api.library(0, filter, sort, libraryId, sortDirection)
        if (generation !== requestGeneration.current) return
        setTracks(fresh.items)
        setTotal(fresh.total)
        setRevision(fresh.revision)
        setNextOffset(fresh.offset + fresh.items.length)
        setSelected([])
        onDiscover(fresh.items)
      } else {
        setTracks((current) => mergeTrackPage(current, page.items))
        setNextOffset(page.offset + page.items.length)
        onDiscover(page.items)
      }
    } catch (error) { if (generation === requestGeneration.current) onNotice(readError(error)) } finally { if (generation === requestGeneration.current) setLoadingMore(false) }
  }
  const scan = async () => {
    requestGeneration.current++
    setLoadingMore(false)
    try {
      await api.scan()
      await onRefresh()
      requestGeneration.current++
      await reloadCurrentView()
      onNotice('NAS 曲库扫描完成')
    } catch (error) { onNotice(readError(error)) }
  }
  const remove = async (track: Track) => {
    if (!window.confirm(`将《${track.title}》移入 NAS 回收区？`)) return
    requestGeneration.current++
    setLoadingMore(false)
    try { await api.deleteTrack(track.id); await onRefresh(); requestGeneration.current++; await reloadCurrentView(); onNotice('音乐已移入服务端回收区') } catch (error) { onNotice(readError(error)) }
  }
  const analyzeTrack = async (track: Track) => {
    setAnalysisBusy(true)
    setTracks((current) => current.map((item) => item.id === track.id ? { ...item, analysis: { ...item.analysis, status: 'queued', progress: .05, message: '等待分析' } } : item))
    try {
      const result = await api.analyze([track.id], false)
      onPollAnalysis(track.id)
      setAnalysisSummary(await api.analysis())
      onNotice(result.queued ? `《${track.title}》已加入分析队列` : `《${track.title}》已在分析队列中`)
    } catch (error) {
      await reloadCurrentView().catch(() => undefined)
      onNotice(readError(error))
    } finally { setAnalysisBusy(false) }
  }
  const analyzeAll = async () => {
    setAnalysisBusy(true)
    try {
      const result = await api.analyze([], true)
      setAnalysisSummary(await api.analysis())
      onNotice(result.queued ? `${result.queued} 首曲目已加入分析队列` : '没有待分析的曲目')
    } catch (error) { onNotice(readError(error)) } finally { setAnalysisBusy(false) }
  }
  return <>
    <div className="library-view-tabs" role="tablist" aria-label="曲库视图"><button role="tab" aria-selected={view === 'tracks'} className={view === 'tracks' ? 'active' : ''} onClick={() => setView('tracks')}>歌曲</button><button role="tab" aria-selected={view === 'analysis'} className={view === 'analysis' ? 'active' : ''} onClick={() => setView('analysis')}>分析</button></div>
    {view === 'analysis' ? <><LibraryAnalysisView tracks={tracks} summary={analysisSummary} onPlay={onPlay} onAnalyze={(track) => void analyzeTrack(track)} onAnalyzeAll={() => void analyzeAll()} busy={analysisBusy} />{tracks.length < total && <button className="load-more secondary-button" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? '正在加载…' : `继续加载分析列表（剩余 ${total - tracks.length} 首）`}</button>}</> : <>
      <div className="toolbar"><label className="filter-field"><Icon name="search" /><input value={filter} onChange={(event) => { requestGeneration.current++; setLoadingMore(false); setFilter(event.target.value) }} placeholder="搜索整个 NAS 曲库" /></label><select className="library-filter" aria-label="按音频库筛选" value={libraryId} onChange={(event) => { requestGeneration.current++; setLoadingMore(false); setLibraryId(event.target.value) }}><option value="">全部音频库</option>{libraries.map((library) => <option key={library.id} value={library.id}>{library.name}{library.status === 'offline' ? '（离线）' : ''}</option>)}</select><select className="library-filter library-sort" aria-label="曲库排序" value={sort} onChange={(event) => { requestGeneration.current++; setLoadingMore(false); setSort(event.target.value as LibrarySort) }}><option value="title">按标题</option><option value="artist">按歌手</option><option value="album">按专辑</option><option value="scanned">按最近扫描时间</option><option value="modified">按文件更新时间</option><option value="bpm">按 BPM</option><option value="key">按调性</option><option value="energy">按能量</option><option value="analysis">按分析状态</option></select><button className="secondary-button sort-direction" aria-label={`当前${sortDirection === 'asc' ? '升序' : '降序'}，点击切换为${sortDirection === 'asc' ? '降序' : '升序'}`} onClick={() => { requestGeneration.current++; setLoadingMore(false); setSortDirection((value) => value === 'asc' ? 'desc' : 'asc') }}>{sortDirection === 'asc' ? '升序 ↑' : '降序 ↓'}</button><div className="toolbar-actions">{selected.length > 0 && <button className="primary-button" onClick={() => { const chosen = tracks.filter((track) => selected.includes(track.id)); if (chosen[0]) onPlay(chosen[0], chosen) }}>播放选中（{selected.length}）</button>}<button className="secondary-button" onClick={() => void scan()}><Icon name="refresh" />重新扫描</button></div></div>
      <section className="track-section"><div className="section-heading"><div><h2>{filter ? `${total} 首匹配` : `${total} 首音乐`}</h2><p>已加载 {tracks.length} / {total} 首 · 筛选和排序覆盖所选音频库</p></div>{tracks[0] && <button className="round-play" onClick={() => onPlay(tracks[0], tracks)} aria-label="播放当前已加载曲目"><Icon name="play" /></button>}</div><TrackList tracks={tracks} favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} selectedIds={selected} onSelection={setSelected} sort={sort} sortDirection={sortDirection} onSort={(value, direction) => { requestGeneration.current++; setLoadingMore(false); setSort(value); setSortDirection(direction) }} virtualized trailingAction={(track) => { const owner = libraries.find((item) => item.id === track.libraryId); return owner && (!owner.enabled || owner.readOnly || owner.status !== 'online') ? null : <button className="icon-button" onClick={() => void remove(track)} aria-label={`将 ${track.title} 移入回收区`}><Icon name="trash" /></button> }} />{tracks.length < total && <button className="load-more secondary-button" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? '正在加载…' : `继续加载（剩余 ${total - tracks.length} 首）`}</button>}</section>
    </>}
  </>
}

function PlaylistsPage({ playlists, selected, setSelected, library, onPlay, onRefresh, onNotice }: { playlists: PlaylistSummary[]; selected: PlaylistDetail | null; setSelected: (value: PlaylistDetail | null) => void; library: Track[]; onPlay: (track: Track, queue: Track[]) => void; onRefresh: () => Promise<void>; onNotice: (value: string) => void }) {
  const [name, setName] = useState('')
  const create = async (event: FormEvent) => {
    event.preventDefault(); if (!name.trim()) return
    try { await api.createPlaylist(name); setName(''); await onRefresh() } catch (error) { onNotice(readError(error)) }
  }
  const open = async (id: string) => { try { setSelected(await api.playlist(id)) } catch (error) { onNotice(readError(error)) } }
  const add = async (track: Track) => {
    if (!selected) return
    try { setSelected(await api.updatePlaylist(selected.id, [...selected.tracks.map((item) => item.id), track.id], selected.version)); await onRefresh() } catch (error) { onNotice(readError(error)) }
  }
  const move = async (index: number, delta: -1 | 1) => {
    if (!selected) return
    const target = index + delta
    if (target < 0 || target >= selected.tracks.length) return
    const next = [...selected.tracks]
    const [item] = next.splice(index, 1)
    next.splice(target, 0, item)
    try {
      const updated = await api.updatePlaylist(selected.id, next.map((track) => track.id), selected.version)
      setSelected(updated)
      await onRefresh()
    } catch (error) { onNotice(readError(error)) }
  }
  if (selected) return <>
    <button className="back-button" onClick={() => setSelected(null)}><Icon name="back" />返回歌单</button>
    <section className="playlist-detail-hero"><div className={`playlist-hero-art ${selected.tracks.length > 1 ? 'mosaic' : ''}`}>{selected.tracks.length ? selected.tracks.slice(0, 4).map((track) => <AlbumArt key={track.id} title={track.title} artworkUrl={track.artworkUrl} />) : <span className="playlist-hero-empty"><Icon name="playlist" /></span>}</div><div className="playlist-hero-copy"><span>共享歌单</span><h2>{selected.name}</h2><p>{selected.tracks.length} 首 · {formatTime(selected.tracks.reduce((total, track) => total + track.durationMs, 0) / 1000)} · 局域网共享</p><div className="playlist-hero-actions"><button className="secondary-button" disabled={!selected.tracks.length} onClick={() => { const shuffled = [...selected.tracks].sort(() => Math.random() - .5); if (shuffled[0]) onPlay(shuffled[0], shuffled) }}><Icon name="sparkles" />随机播放</button><button className="playlist-main-play" disabled={!selected.tracks.length} onClick={() => { if (selected.tracks[0]) onPlay(selected.tracks[0], selected.tracks) }} aria-label={`播放歌单 ${selected.name}`}><Icon name="play" />播放</button></div></div></section>
    <section className="track-section"><div className="section-heading"><div><h2>歌曲</h2><p>使用上下按钮调整共享顺序，其他访客会看到相同结果</p></div></div><TrackList tracks={selected.tracks} favorites={[]} onPlay={onPlay} trailingAction={(track) => { const index = selected.tracks.findIndex((item) => item.id === track.id); return <span className="playlist-order-actions"><button className="icon-button" disabled={index <= 0} onClick={() => void move(index, -1)} aria-label={`上移 ${track.title}`}><Icon name="back" /></button><button className="icon-button" disabled={index < 0 || index >= selected.tracks.length - 1} onClick={() => void move(index, 1)} aria-label={`下移 ${track.title}`}><Icon name="back" /></button></span> }} /></section>
    <details className="add-tracks"><summary>添加 NAS 曲目</summary><div className="compact-track-list">{library.slice(0, 50).map((track) => <button key={track.id} onClick={() => void add(track)}><span>{track.title}</span><small>{track.artist}</small><b>＋</b></button>)}</div></details>
  </>
  return <>
    <form className="inline-create" onSubmit={create}><input value={name} onChange={(event) => setName(event.target.value)} placeholder="新歌单名称" aria-label="新歌单名称" /><button className="primary-button">创建歌单</button></form>
    <div className="playlist-grid">{playlists.map((playlist) => <button key={playlist.id} className="playlist-tile" onClick={() => void open(playlist.id)}><AlbumArt title={playlist.name} /><strong>{playlist.name}</strong><span>{playlist.trackCount} 首 · 刚刚更新</span></button>)}</div>
    {!playlists.length && <EmptyState title="还没有共享歌单" body="创建一张歌单，局域网内的每个人都能继续整理。" />}
  </>
}

function RoomsPage({ rooms, room, onRefresh, onNotice }: { rooms: RoomSummary[]; room: ReturnType<typeof useRoomSync>; onRefresh: () => Promise<void>; onNotice: (value: string) => void }) {
  const [name, setName] = useState('全屋同步')
  const create = async (event: FormEvent) => {
    event.preventDefault()
    const id = randomId()
    let creation: Promise<RoomSummary> | undefined
    try {
      await room.join(id, () => { creation = api.createRoom(name, id); return creation })
      await creation
      await onRefresh()
    } catch (error) { onNotice(readError(error)) }
  }
  const join = async (id: string) => {
    try { await room.join(id) } catch (error) { onNotice(readError(error)) }
  }
  const remove = async (item: RoomSummary) => {
    if (!window.confirm(`确定删除同步房间“${item.name}”吗？房间内设备会立即断开。`)) return
    try {
      await api.deleteRoom(item.id)
      if (room.roomId === item.id) room.leave()
      await onRefresh()
      onNotice('同步房间已删除')
    } catch (error) { onNotice(readError(error)) }
  }
  return <>
    <section className="room-intro"><div><h2>让不同设备连接的音响一起播放</h2><p>同步由 Sendspin 负责。每台设备需主动加入并启用声音，蓝牙链路可用静态延迟校准。</p></div><span className="latency-readout"><strong>自动时钟校准</strong><small>NAS 时钟偏移 {Math.round(room.serverOffset)} ms · 持续调整</small></span></section>
    <form className="inline-create" onSubmit={create}><input value={name} onChange={(event) => setName(event.target.value)} aria-label="房间名称" /><button className="primary-button">创建并加入</button></form>
    <div className="room-list">{rooms.map((item) => <div key={item.id} className={`room-row ${room.roomId === item.id ? 'active' : ''}`}><span className="speaker-glyph"><Icon name="speaker" /></span><div><strong>{item.name}</strong><small>{item.memberCount} 台设备在线</small></div><span className="room-actions">{room.roomId === item.id ? <button className="secondary-button" onClick={room.leave}>离开</button> : <button className="primary-button" onClick={() => void join(item.id)}>加入并启用声音</button>}<button className="icon-button danger-button" onClick={() => void remove(item)} aria-label={`删除房间 ${item.name}`} title="删除房间"><Icon name="trash" /></button></span></div>)}</div>
    <details className="delay-calibration"><summary>高级：外部输出固定延迟校准</summary><p>仅校正蓝牙、声卡或音响 DSP 的稳定链路延迟；Sendspin 自动同步仍会持续运行。</p><label className="delay-control"><span>固定输出补偿 <b>{room.deviceDelay} ms</b></span><input type="range" min="0" max="5000" step="10" value={room.deviceDelay} onChange={(event) => room.setDeviceDelay(Number(event.target.value))} /></label></details>
  </>
}

function HistoryPage({ history, favorites, onPlay, onFavorite }: { history: HistoryEntry[]; favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void }) {
  const tracks = history.map((entry) => entry.track)
  const days = history.reduce<Record<string, HistoryEntry[]>>((groups, entry) => {
    const key = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' }).format(new Date(entry.playedAt))
    ;(groups[key] ??= []).push(entry)
    return groups
  }, {})
  if (!history.length) return <EmptyState title="还没有播放历史" body="从首页、搜索或 NAS 曲库播放音乐后，会按日期保留在这里。" />
  return <div className="history-page">{Object.entries(days).map(([day, entries]) => <section className="track-section" key={day}><div className="section-heading"><div><h2>{day}</h2><p>{entries.length} 次播放</p></div><button className="round-play" onClick={() => onPlay(entries[0].track, tracks)} aria-label={`播放${day}的历史记录`}><Icon name="play" /></button></div><TrackList tracks={entries.map((entry) => entry.track)} favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} /></section>)}</div>
}

function StatsPage({ history, library, favorites }: { history: HistoryEntry[]; library: Track[]; favorites: Track[] }) {
  const totalSeconds = history.reduce((sum, entry) => sum + entry.track.durationMs / 1000, 0)
  const artistCounts = history.reduce<Record<string, number>>((counts, entry) => {
    const artist = entry.track.artist || '未知艺术家'
    counts[artist] = (counts[artist] ?? 0) + 1
    return counts
  }, {})
  const topArtists = Object.entries(artistCounts).sort((a, b) => b[1] - a[1]).slice(0, 6)
  const analyzed = library.filter((track) => track.analysis?.status === 'completed')
  const bpmTracks = analyzed.filter((track) => track.analysis?.bpm)
  const averageBpm = bpmTracks.length ? Math.round(bpmTracks.reduce((sum, track) => sum + (track.analysis?.bpm ?? 0), 0) / bpmTracks.length) : 0
  return <div className="stats-page">
    <section className="stats-summary" aria-label="收听概览"><div><strong>{history.length}</strong><span>播放次数</span></div><div><strong>{formatListeningTime(totalSeconds)}</strong><span>预计收听</span></div><div><strong>{favorites.length}</strong><span>已点赞</span></div><div><strong>{library.length}</strong><span>曲库歌曲</span></div></section>
    <section className="stats-list"><div className="section-heading"><div><h2>最常听的艺术家</h2><p>根据共享播放历史统计</p></div></div>{topArtists.length ? topArtists.map(([artist, count], index) => <div className="stats-artist" key={artist}><b>{index + 1}</b><span><strong>{artist}</strong><small>{count} 次播放</small></span><i style={{ width: `${Math.max(12, count / topArtists[0][1] * 100)}%` }} /></div>) : <EmptyState compact title="等待收听数据" body="播放几首音乐后，这里会逐渐形成你的收听概览。" />}</section>
    <section className="stats-list"><div className="section-heading"><div><h2>音乐画像概览</h2><p>NAS 曲库的分析覆盖情况</p></div></div><div className="stats-analysis"><span><strong>{analyzed.length}</strong><small>已完成分析</small></span><span><strong>{averageBpm || '—'}</strong><small>平均 BPM</small></span><span><strong>{library.length ? `${Math.round(analyzed.length / library.length * 100)}%` : '0%'}</strong><small>分析覆盖率</small></span></div></section>
  </div>
}

function formatListeningTime(seconds: number) {
  if (seconds < 3600) return `${Math.round(seconds / 60)} 分钟`
  return `${Math.round(seconds / 3600 * 10) / 10} 小时`
}

function SettingsPage({ libraries, sources, downloads, theme, onTheme, onNavigate, onRefresh, onNotice }: { libraries: MusicLibrary[]; sources: SourceConfig[]; downloads: DownloadJob[]; theme: Theme; onTheme: (value: Theme) => void; onNavigate: (section: Section) => void; onRefresh: () => Promise<void>; onNotice: (value: string) => void }) {
  const [panel, setPanel] = useState<'main' | 'appearance' | 'libraries' | 'sources' | 'about'>('main')
  const [form, setForm] = useState({ name: '', apiUrl: '', apiKey: '' })
  const [libraryForm, setLibraryForm] = useState<MusicLibraryInput>({ name: '', path: '/libraries/', deviceType: 'local', readOnly: true, enabled: true, downloadTarget: false })
  const save = async (event: FormEvent) => {
    event.preventDefault()
    try { await api.createSource(form); setForm({ name: '', apiUrl: '', apiKey: '' }); await onRefresh(); onNotice('音乐源已安全保存到 NAS') } catch (error) { onNotice(readError(error)) }
  }
  const addLibrary = async (event: FormEvent) => {
    event.preventDefault()
    try {
      await api.createLibrary(libraryForm)
      setLibraryForm({ name: '', path: '/libraries/', deviceType: 'local', readOnly: true, enabled: true, downloadTarget: false })
      await onRefresh()
      onNotice('音频库已添加，可以开始扫描')
    } catch (error) { onNotice(readError(error)) }
  }
  if (panel === 'main') return <div className="settings-home">
    <SettingsGroup title="界面"><SettingsRow icon="sparkles" title="外观" detail="主题、纯黑模式与界面色彩" onClick={() => setPanel('appearance')} /></SettingsGroup>
    <SettingsGroup title="内容"><SettingsRow icon="storage" title="音频库" detail={`${libraries.length} 个 NAS、USB 或网络音乐库`} onClick={() => setPanel('libraries')} /><SettingsRow icon="search" title="音乐源" detail={`${sources.length} 个在线搜索与下载来源`} onClick={() => setPanel('sources')} /></SettingsGroup>
    <SettingsGroup title="播放与同步"><SettingsRow icon="download" title="下载与缓存" detail={downloads.some((job) => job.status === 'queued' || job.status === 'downloading') ? `${downloads.filter((job) => job.status === 'queued' || job.status === 'downloading').length} 个任务进行中` : '查看下载任务与失败重试'} onClick={() => onNavigate('downloads')} /><SettingsRow icon="room" title="同步房间" detail="Sendspin 多设备同步播放" onClick={() => onNavigate('rooms')} /></SettingsGroup>
    <SettingsGroup title="关于"><SettingsRow icon="album" title="关于 SHiNe MUSIC" detail="版本、运行模式与项目信息" onClick={() => setPanel('about')} /></SettingsGroup>
  </div>
  const back = <button className="settings-back back-button" onClick={() => setPanel('main')}><Icon name="back" />返回设置</button>
  if (panel === 'appearance') return <>{back}<section className="settings-section settings-detail"><h2>外观</h2><p>沿用原版 Material 3 色彩逻辑，选择浅色、深色或适合 OLED 的纯黑主题。</p><div className="theme-options" role="group" aria-label="界面主题">{(['light', 'dark', 'black'] as Theme[]).map((value) => <button key={value} className={theme === value ? 'active' : ''} onClick={() => onTheme(value)} aria-pressed={theme === value}>{({ light: '浅色', dark: '深色', black: '纯黑' })[value]}</button>)}</div></section></>
  if (panel === 'libraries') return <>{back}<section className="settings-section libraries-section settings-detail"><h2>音频库</h2><p>统一管理 NAS、USB 硬盘和网络挂载。设备掉线时保留曲库索引，不会将音乐误判为已删除。</p><form className="settings-form library-create" onSubmit={addLibrary}><label>名称<input required value={libraryForm.name} onChange={(event) => setLibraryForm({ ...libraryForm, name: event.target.value })} placeholder="例如：客厅 USB 硬盘" /></label><label>容器内路径<input required value={libraryForm.path} onChange={(event) => setLibraryForm({ ...libraryForm, path: event.target.value })} placeholder="/libraries/living-room" /></label><label>设备类型<select value={libraryForm.deviceType} onChange={(event) => setLibraryForm({ ...libraryForm, deviceType: event.target.value as MusicLibraryInput['deviceType'] })}><option value="local">NAS 本地</option><option value="usb">USB 设备</option><option value="network">网络挂载</option><option value="cloud">云盘挂载</option></select></label><label className="check-label"><input type="checkbox" checked={libraryForm.readOnly} onChange={(event) => setLibraryForm({ ...libraryForm, readOnly: event.target.checked, downloadTarget: event.target.checked ? false : libraryForm.downloadTarget })} />只读保护</label><label className="check-label"><input type="checkbox" disabled={libraryForm.readOnly} checked={libraryForm.downloadTarget} onChange={(event) => setLibraryForm({ ...libraryForm, downloadTarget: event.target.checked })} />设为在线下载目标</label><button className="primary-button">添加音频库</button></form><div className="library-list">{libraries.map((library) => <LibraryEditor key={library.id} library={library} onRefresh={onRefresh} onNotice={onNotice} />)}</div></section></>
  if (panel === 'sources') return <>{back}<section className="settings-section settings-detail"><h2>音乐源</h2><p>密钥只保存在 NAS，页面只显示末四位。</p><form className="settings-form" onSubmit={save}><label>名称<input required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></label><label>API 地址<input required type="url" value={form.apiUrl} onChange={(event) => setForm({ ...form, apiUrl: event.target.value })} /></label><label>API 密钥<input required type="password" value={form.apiKey} onChange={(event) => setForm({ ...form, apiKey: event.target.value })} /></label><button className="primary-button">保存音乐源</button></form>{sources.map((source) => <div className="source-row" key={source.id}><span><strong>{source.name}</strong><small>{source.apiUrl}</small></span><code>{source.apiKeyMasked}</code></div>)}</section></>
  return <>{back}<section className="about-page"><div className="about-product"><img src={shineLogoUrl} alt="SHiNe MUSIC" /><span><h2>SHiNe MUSIC</h2><p>NAS Web Edition</p><small>原版移动端体验 · 多设备共享</small></span></div><div className="about-copy"><h2>熟悉的 SHiNe，现在运行在你的 NAS</h2><p>手机端界面以原 Android/Compose 版本为准复现；NAS 负责曲库、共享数据和下载，Sendspin 仅承担同步播放底座。</p></div><div className="about-facts"><span><strong>Web</strong><small>React + TypeScript</small></span><span><strong>HTTP</strong><small>局域网普通网页模式</small></span><span><strong>Sendspin</strong><small>多设备同步播放</small></span></div></section></>
}

function SettingsGroup({ title, children }: { title: string; children: ReactNode }) {
  return <section className="settings-group"><h2>{title}</h2><div>{children}</div></section>
}

function SettingsRow({ icon, title, detail, onClick }: { icon: Parameters<typeof Icon>[0]['name']; title: string; detail: string; onClick: () => void }) {
  return <button className="settings-row" onClick={onClick}><i><Icon name={icon} /></i><span><strong>{title}</strong><small>{detail}</small></span><b aria-hidden="true">›</b></button>
}

function LibraryEditor({ library, onRefresh, onNotice }: { library: MusicLibrary; onRefresh: () => Promise<void>; onNotice: (value: string) => void }) {
  const [name, setName] = useState(library.name)
  const [deviceType, setDeviceType] = useState(library.deviceType)
  useEffect(() => { setName(library.name); setDeviceType(library.deviceType) }, [library.deviceType, library.name])
  const update = async (changes: Partial<MusicLibraryInput>, message: string) => {
    try {
      await api.updateLibrary(library.id, { name, path: library.path, deviceType: library.deviceType, readOnly: library.readOnly, enabled: library.enabled, downloadTarget: library.downloadTarget, ...changes })
      await onRefresh()
      onNotice(message)
    } catch (error) { onNotice(readError(error)) }
  }
  const scan = async () => {
    try { await api.scanLibrary(library.id); await onRefresh(); onNotice(`${library.name} 扫描完成`) } catch (error) { onNotice(readError(error)) }
  }
  const confirmEmpty = async () => {
    if (!window.confirm(`确认《${library.name}》已被主动清空？这会从曲库移除该库的所有索引。`)) return
    try { await api.scanLibrary(library.id, true); await onRefresh(); onNotice('已确认空曲库并更新索引') } catch (error) { onNotice(readError(error)) }
  }
  const configurationChanged = name.trim() !== library.name || deviceType !== library.deviceType
  return <article className="library-row"><div className="library-summary"><div><input aria-label={`${library.name} 的名称`} value={name} onChange={(event) => setName(event.target.value)} /><select aria-label={`${library.name} 的设备类型`} value={deviceType} onChange={(event) => setDeviceType(event.target.value as MusicLibrary['deviceType'])}><option value="local">NAS 本地</option><option value="usb">USB 设备</option><option value="network">网络挂载</option><option value="cloud">云盘挂载</option></select><small>{library.path} · {library.trackCount} 首 · {library.readOnly ? '只读' : '可写'}</small></div><span className={`status-badge ${library.status}`}>{libraryStatusLabel(library.status)}</span></div>{library.lastError && <small className="library-error">最近状态：{library.lastError}</small>}<div className="library-actions"><button className="secondary-button" disabled={!name.trim() || !configurationChanged} onClick={() => void update({ name, deviceType }, '音频库设置已更新')}>保存设置</button><button className="secondary-button" disabled={!library.enabled} onClick={() => void scan()}><Icon name="refresh" />扫描</button>{library.lastError === 'empty_mount' && <button className="secondary-button" onClick={() => void confirmEmpty()}>确认已清空</button>}<button className="secondary-button" onClick={() => void update({ enabled: !library.enabled, downloadTarget: library.enabled ? false : library.downloadTarget }, library.enabled ? '音频库已停用，索引仍保留' : '音频库已启用')}>{library.enabled ? '停用' : '启用'}</button><button className="secondary-button" disabled={library.downloadTarget} onClick={() => void update({ readOnly: !library.readOnly }, library.readOnly ? '音频库已允许写入' : '音频库已设为只读')}>{library.readOnly ? '允许写入' : '设为只读'}</button>{library.downloadTarget ? <button className="secondary-button" onClick={() => void update({ downloadTarget: false }, '已取消下载目标')}>取消下载目标</button> : !library.readOnly && library.enabled && library.status === 'online' && <button className="secondary-button" onClick={() => void update({ downloadTarget: true }, '已设为在线下载目标')}>设为下载目标</button>}</div></article>
}

function libraryStatusLabel(status: MusicLibrary['status']) { return ({ online: '已连接', offline: '设备离线', disabled: '已停用', unknown: '待扫描' })[status] }

function roomConnectionLabel(room: ReturnType<typeof useRoomSync>) {
  if (!room.roomId) return '独立播放'
  if (room.status === 'joined') return `${room.members} 台设备同步中`
  if (room.status === 'connecting') return 'Sendspin 正在连接'
  return room.status === 'reconnecting' ? 'Sendspin 正在重连' : 'Sendspin 连接异常'
}

function LoadingRows() {
  return <div className="loading-rows" aria-label="加载中">{Array.from({ length: 7 }, (_, index) => <span key={index} />)}</div>
}

async function copyText(value: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value)
    return
  }
  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.append(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  textarea.remove()
  if (!copied) throw new Error('copy_not_supported')
}

function readError(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}
