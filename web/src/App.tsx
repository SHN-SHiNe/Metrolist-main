import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api'
import { LIBRARY_PAGE_SIZE, mergeTrackPage } from './libraryPaging'
import { desktopNavigation, sectionFromHash, type Section } from './navigation'
import { randomId } from './randomId'
import { mergeSimilarQueue, recentTrackIds } from './similarAutoplay'
import type { DownloadJob, HistoryEntry, MusicLibrary, MusicLibraryInput, PlaylistDetail, PlaylistSummary, RoomSummary, SimilarTracksResponse, SourceConfig, Track } from './types'
import { usePlayer } from './usePlayer'
import { useRoomSync } from './useRoomSync'
import { AdvancedSearchPanel } from './components/AdvancedSearchPanel'
import { Icon } from './components/Icon'
import { AlbumArt, AlbumRow, EmptyState, TrackList, TrackSection } from './components/TrackList'
import { RadarChart } from './components/RadarChart'
import { DesktopShell, MobileShell } from './layout/Shells'
import { NowPlaying, AnalysisChips } from './player/NowPlaying'
import { PlayerBar } from './player/PlayerBar'

type Theme = 'light' | 'dark' | 'black'

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
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState<string | null>(null)
  const [nowPlayingOpen, setNowPlayingOpen] = useState(false)
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem('shine-theme') as Theme) || 'dark')
  const [queueOpen, setQueueOpen] = useState(true)
  const [queueTab, setQueueTab] = useState<'queue' | 'similar'>('queue')
  const [similarByTrack, setSimilarByTrack] = useState<Record<string, SimilarTracksResponse>>({})
  const [similarLoadingId, setSimilarLoadingId] = useState<string | null>(null)
  const sessionRecent = useRef<string[]>([])
  const autoPrefetched = useRef<string | null>(null)
  const analysisPollTimers = useRef(new Map<string, number>())
  const player = usePlayer()
  const activeTrackId = useRef<string | null>(player.current?.id ?? null)
  activeTrackId.current = player.current?.id ?? null
  const allTracks = useMemo(() => [...library, ...searchResults, ...favorites, ...history.map((entry) => entry.track), ...Object.values(similarByTrack).flatMap((response) => [response.seed, ...response.items.map((item) => item.track)])].filter((track, index, array) => array.findIndex((item) => item.id === track.id) === index), [favorites, history, library, searchResults, similarByTrack])
  const room = useRoomSync(player, allTracks)

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
    location.hash = next
    setSection(next)
  }

  const rememberPlayed = useCallback((id: string) => {
    sessionRecent.current = recentTrackIds(sessionRecent.current, id)
  }, [])

  const loadSimilar = useCallback(async (track: Track, force = false) => {
    if (!force && similarByTrack[track.id]) return similarByTrack[track.id]
    setSimilarLoadingId(track.id)
    try {
      const recent = recentTrackIds([
        ...history.map((entry) => entry.track.id).reverse(),
        ...sessionRecent.current,
      ], track.id)
      const response = await api.similar(track.id, 24, recent)
      setSimilarByTrack((current) => ({ ...current, [track.id]: response }))
      return response
    } catch (error) {
      if (force) setNotice(readError(error))
      return null
    } finally {
      setSimilarLoadingId((current) => current === track.id ? null : current)
    }
  }, [history, similarByTrack])

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
      room.command({ queue: queue.map((item) => item.id), currentTrackId: track.id, positionMs: 0, playing: true })
    } else player.playTrack(track, queue)
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
      if (!response || room.roomId || activeTrackId.current !== seedId) return
      const additions = mergeSimilarQueue(player.queue, response.items, recentTrackIds(sessionRecent.current, player.current?.id))
      player.appendTracks(additions)
    })
  }, [loadSimilar, player, room.roomId])

  useEffect(() => {
    const onEnded = () => {
      if (room.roomId) { skipPlayback(1); return }
      if (player.index < player.queue.length - 1) { player.next(); return }
      if (!player.current) return
      rememberPlayed(player.current.id)
      const seedId = player.current.id
      void loadSimilar(player.current, true).then((response) => {
        if (!response || room.roomId || activeTrackId.current !== seedId) return
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

  const analyzeCurrent = async () => {
    if (!player.current) return
    const original = player.current
    if (original.id.toLowerCase().startsWith('online-')) {
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

  const title = navItems.find((item) => item.id === section)?.label ?? '设置'
  const currentSimilar = player.current ? similarByTrack[player.current.id] ?? null : null

  return (
    <div className={`app-shell ${queueOpen ? '' : 'queue-closed'}`}>
      <DesktopShell active={section} onNavigate={navigate} queueOpen={queueOpen} queue={<>
        <div className="panel-tabs" role="tablist" aria-label="播放侧栏"><button role="tab" aria-selected={queueTab === 'queue'} className={queueTab === 'queue' ? 'active' : ''} onClick={() => setQueueTab('queue')}>队列 <span>{player.queue.length}</span></button><button role="tab" aria-selected={queueTab === 'similar'} className={queueTab === 'similar' ? 'active' : ''} onClick={() => { setQueueTab('similar'); if (player.current) void loadSimilar(player.current) }}>相似</button></div>
        {queueTab === 'queue' ? player.queue.length ? player.queue.map((track, index) => (
          <button key={`${track.id}-${index}`} className={`queue-item ${index === player.index ? 'active' : ''}`} onClick={() => play(track, player.queue)}>
            <span className="queue-number">{index === player.index && player.playing ? '▶' : index + 1}</span><AlbumArt title={track.title} artworkUrl={track.artworkUrl} small /><span><strong>{track.title}</strong><small>{track.artist}</small></span>
          </button>
        )) : <EmptyState compact title="队列还是空的" body="从曲库或搜索结果中选择一首歌。" /> : currentSimilar?.items.length ? <div className="panel-similar-list">{currentSimilar.items.map((item) => <button key={item.track.id} onClick={() => play(item.track, currentSimilar.items.map((match) => match.track))}><RadarChart analysis={item.track.analysis} compact /><span><strong>{item.track.title}</strong><small>{item.track.artist}</small><AnalysisChips track={item.track} compact /></span><em>{item.similarityPercent}%</em></button>)}</div> : <EmptyState compact title={similarLoadingId ? '正在计算相似音乐' : '还没有相似推荐'} body="完成曲目分析后，会按节拍、调性和七维听感延续播放。" />}
      </>} />

      <main className="main-content" id="main-content">
        <header className="topbar">
          <div><p className="eyeline">SHiNe MUSIC</p><h1>{title}</h1></div>
          <div className="topbar-actions">
            <button className={`connection ${room.status}`} title={`Sendspin 同步误差 ${Math.round(room.serverOffset)}ms`} onClick={() => navigate('rooms')}><span className="status-dot" />{roomConnectionLabel(room)}</button>
            <button className="topbar-icon" onClick={() => navigate('settings')} aria-label="打开设置"><Icon name="settings" /></button>
            <button className="queue-toggle secondary-button" onClick={() => setQueueOpen((value) => !value)} aria-expanded={queueOpen} aria-controls="play-queue">{queueOpen ? '收起队列' : '展开队列'}</button>
          </div>
        </header>
        {notice && <div className="notice" role="status"><span>{notice}</span><button onClick={() => setNotice(null)} aria-label="关闭提示">×</button></div>}
        {loading ? <LoadingRows /> : (
          <div className="page-content">
            {section === 'home' && <HomePage history={history} favorites={favorites} library={library} libraryTotal={libraryTotal} playlists={playlists} similar={currentSimilar} current={player.current} onPlay={play} onNavigate={navigate} onLoadSimilar={() => { if (player.current) void loadSimilar(player.current) }} />}
            {section === 'search' && <SearchPage query={query} setQuery={setQuery} results={searchResults} setResults={setSearchResults} onPlay={play} onFavorite={toggleFavorite} favorites={favorites} onNotice={setNotice} />}
            {section === 'library' && <LibraryHub playlists={playlists} favorites={favorites} selected={selectedPlaylist} setSelected={setSelectedPlaylist} library={library} onPlay={play} onFavorite={toggleFavorite} onRefresh={refresh} onNotice={setNotice} />}
            {section === 'local' && <LibraryPage libraries={libraries} initialTracks={library} initialTotal={libraryTotal} initialRevision={libraryRevision} favorites={favorites} onPlay={play} onFavorite={toggleFavorite} onDiscover={rememberLibraryTracks} onRefresh={refresh} onNotice={setNotice} />}
            {section === 'rooms' && <RoomsPage rooms={rooms} room={room} onRefresh={refresh} onNotice={setNotice} />}
            {section === 'settings' && <SettingsPage libraries={libraries} sources={sources} downloads={downloads} theme={theme} onTheme={setTheme} onRefresh={refresh} onNotice={setNotice} />}
          </div>
        )}
      </main>

      <PlayerBar player={player} room={room} favorite={Boolean(player.current && favorites.some((track) => track.id === player.current?.id))} onFavorite={() => { if (player.current) void toggleFavorite(player.current) }} onToggle={togglePlayback} onPrevious={() => skipPlayback(-1)} onNext={() => skipPlayback(1)} onSeek={seekPlayback} onOpen={() => setNowPlayingOpen(true)} />
      <MobileShell active={section} onNavigate={navigate} />
      {nowPlayingOpen && <NowPlaying player={player} favorite={Boolean(player.current && favorites.some((track) => track.id === player.current?.id))} similar={currentSimilar} similarLoading={similarLoadingId === player.current?.id} onFavorite={() => { if (player.current) void toggleFavorite(player.current) }} onToggle={togglePlayback} onPrevious={() => skipPlayback(-1)} onNext={() => skipPlayback(1)} onSeek={seekPlayback} onClose={() => setNowPlayingOpen(false)} onLoadSimilar={() => { if (player.current) void loadSimilar(player.current) }} onAnalyze={() => void analyzeCurrent()} onPlaySimilar={play} />}
    </div>
  )
}

function HomePage({ history, favorites, library, libraryTotal, playlists, current, similar, onPlay, onNavigate, onLoadSimilar }: { history: HistoryEntry[]; favorites: Track[]; library: Track[]; libraryTotal: number; playlists: PlaylistSummary[]; current: Track | null; similar: SimilarTracksResponse | null; onPlay: (track: Track, queue: Track[]) => void; onNavigate: (section: Section) => void; onLoadSimilar: () => void }) {
  const recent = history.slice(0, 8).map((entry) => entry.track)
  const recommendations = similar?.items.map((item) => item.track) ?? library.filter((track) => track.analysis?.status === 'completed').slice(0, 10)
  const recommendationBadges = new Map(similar?.items.map((item) => [item.track.id, `${item.similarityPercent}% 相似`]) ?? [])
  return <>
    <section className="home-greeting">
      <div><span className="section-kicker">SHiNe MUSIC</span><h2>听点什么？</h2><p>{libraryTotal} 首音乐已在家里的 NAS 上准备好</p></div>
      {library[0] && <button className="round-play" onClick={() => onPlay(library[0], library)} aria-label="播放 NAS 曲库"><Icon name="play" /></button>}
    </section>
    <section className="content-rail"><div className="section-heading"><div><h2>快速访问</h2><p>熟悉的位置，也连接着家里的音乐</p></div></div><div className="quick-grid">
      <button onClick={() => onNavigate('local')}><span className="quick-art four-covers">{library.slice(0, 4).map((track) => <AlbumArt key={track.id} title={track.title} artworkUrl={track.artworkUrl} small />)}</span><strong>NAS 曲库</strong><small>{libraryTotal} 首音乐</small></button>
      <button onClick={() => onNavigate('library')}><span className="quick-art liked"><Icon name="heart" /></span><strong>已点赞</strong><small>{favorites.length} 首音乐</small></button>
      <button onClick={() => onNavigate('library')}><span className="quick-art playlists"><Icon name="playlist" /></span><strong>播放列表</strong><small>{playlists.length} 个共享歌单</small></button>
      <button onClick={() => onNavigate('rooms')}><span className="quick-art room"><Icon name="room" /></span><strong>同步房间</strong><small>让多台音响一起播放</small></button>
    </div></section>
    <AlbumRow title="继续听" tracks={(recent.length ? recent : favorites).slice(0, 8)} onPlay={onPlay} />
    <section className="content-rail"><div className="section-heading"><div><h2>相似推荐</h2><p>{current ? `延续《${current.title}》的节拍、调性与听感` : '播放一首已分析音乐后，从这里自然续播'}</p></div>{current && <button className="text-button" onClick={onLoadSimilar}><Icon name="sparkles" />重新计算</button>}</div><AlbumRow title="" tracks={recommendations} badges={recommendationBadges} onPlay={onPlay} />{!recommendations.length && <EmptyState compact title="等待音乐画像" body="NAS 完成曲目分析后，相似推荐会出现在这里。" />}</section>
    <AlbumRow title="最近播放" tracks={recent} onPlay={onPlay} />
  </>
}

function SearchPage({ query, setQuery, results, setResults, onPlay, onFavorite, favorites, onNotice }: {
  query: string; setQuery: (value: string) => void; results: Track[]; setResults: (value: Track[]) => void
  onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void; favorites: Track[]; onNotice: (value: string) => void
}) {
  const [searching, setSearching] = useState(false)
  const [source, setSource] = useState('all')
  const [mode, setMode] = useState<'online' | 'advanced'>('online')
  const search = async (event: FormEvent) => {
    event.preventDefault()
    if (!query.trim()) return
    setSearching(true)
    try { setResults((await api.search(query, source)).items) } catch (error) { onNotice(readError(error)) } finally { setSearching(false) }
  }
  const download = async (track: Track) => {
    try { await api.download(track); onNotice(`《${track.title}》已加入 NAS 下载队列`) } catch (error) { onNotice(readError(error)) }
  }
  return <>
    <div className="search-mode-tabs" role="tablist" aria-label="搜索方式"><button role="tab" aria-selected={mode === 'online'} className={mode === 'online' ? 'active' : ''} onClick={() => setMode('online')}><Icon name="search" />在线与曲库</button><button role="tab" aria-selected={mode === 'advanced'} className={mode === 'advanced' ? 'active' : ''} onClick={() => setMode('advanced')}><Icon name="radar" />高级听感</button></div>
    {mode === 'advanced' ? <AdvancedSearchPanel favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} onNotice={onNotice} /> : <><form className="search-box" onSubmit={search} role="search">
      <Icon name="search" /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索歌曲、歌手或专辑" aria-label="在线搜索" />
      <button className="primary-button" disabled={searching}>{searching ? '搜索中…' : '搜索'}</button>
    </form>
    <div className="source-chips" role="group" aria-label="搜索音源">{[['all', '聚合'], ['netease', '网易云'], ['qq', 'QQ'], ['kugou', '酷狗'], ['kuwo', '酷我']].map(([value, label]) => <button key={value} className={source === value ? 'active' : ''} aria-pressed={source === value} onClick={() => setSource(value)}>{label}</button>)}</div>
    {results.length ? <TrackList tracks={results} favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} trailingAction={(track) => <button className="icon-button" onClick={() => void download(track)} title="下载到 NAS"><Icon name="download" /></button>} /> : <EmptyState title="从五大音源开始搜索" body="搜索结果可以直接播放，也可以下载到 NAS 永久保存。" />}</>}
  </>
}

function LibraryHub({ playlists, favorites, selected, setSelected, library, onPlay, onFavorite, onRefresh, onNotice }: { playlists: PlaylistSummary[]; favorites: Track[]; selected: PlaylistDetail | null; setSelected: (value: PlaylistDetail | null) => void; library: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void; onRefresh: () => Promise<void>; onNotice: (value: string) => void }) {
  const [tab, setTab] = useState<'playlists' | 'favorites'>('playlists')
  if (selected) return <PlaylistsPage playlists={playlists} selected={selected} setSelected={setSelected} library={library} onPlay={onPlay} onRefresh={onRefresh} onNotice={onNotice} />
  return <>
    <div className="library-tabs" role="tablist" aria-label="音乐库分类"><button role="tab" aria-selected={tab === 'playlists'} className={tab === 'playlists' ? 'active' : ''} onClick={() => setTab('playlists')}>播放列表</button><button role="tab" aria-selected={tab === 'favorites'} className={tab === 'favorites' ? 'active' : ''} onClick={() => setTab('favorites')}>已点赞</button></div>
    {tab === 'playlists' ? <PlaylistsPage playlists={playlists} selected={null} setSelected={setSelected} library={library} onPlay={onPlay} onRefresh={onRefresh} onNotice={onNotice} /> : <TrackSection title="已点赞" subtitle={`${favorites.length} 首 · 家庭共享`} tracks={favorites} favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} />}
  </>
}

function LibraryPage({ libraries, initialTracks, initialTotal, initialRevision, favorites, onPlay, onFavorite, onDiscover, onRefresh, onNotice }: { libraries: MusicLibrary[]; initialTracks: Track[]; initialTotal: number; initialRevision: number; favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void; onDiscover: (tracks: Track[]) => void; onRefresh: () => Promise<void>; onNotice: (value: string) => void }) {
  const [filter, setFilter] = useState('')
  const [sort, setSort] = useState<'title' | 'artist' | 'album'>('artist')
  const [libraryId, setLibraryId] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [tracks, setTracks] = useState(() => initialTracks.slice(0, LIBRARY_PAGE_SIZE))
  const [total, setTotal] = useState(initialTotal)
  const [revision, setRevision] = useState(initialRevision)
  const [nextOffset, setNextOffset] = useState(Math.min(initialTracks.length, LIBRARY_PAGE_SIZE))
  const [loadingMore, setLoadingMore] = useState(false)
  const requestGeneration = useRef(0)
  const filterRef = useRef(filter)
  const sortRef = useRef(sort)
  const libraryRef = useRef(libraryId)
  filterRef.current = filter
  sortRef.current = sort
  libraryRef.current = libraryId
  useEffect(() => {
    let cancelled = false
    const generation = requestGeneration.current
    const timer = window.setTimeout(() => {
      void api.library(0, filter, sort, libraryId).then((page) => {
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
  }, [filter, libraryId, onDiscover, onNotice, sort])
  const reloadCurrentView = async () => {
    const generation = requestGeneration.current
    const page = await api.library(0, filterRef.current, sortRef.current, libraryRef.current)
    if (generation !== requestGeneration.current) return
    setTracks(page.items)
    setTotal(page.total)
    setRevision(page.revision)
    setNextOffset(page.offset + page.items.length)
    setSelected([])
    onDiscover(page.items)
  }
  const loadMore = async () => {
    if (loadingMore || nextOffset >= total) return
    const generation = requestGeneration.current
    setLoadingMore(true)
    try {
      const page = await api.library(nextOffset, filter, sort, libraryId)
      if (generation !== requestGeneration.current) return
      const knownIds = new Set(tracks.map((track) => track.id))
      if (page.revision !== revision || page.total !== total || page.items.some((track) => knownIds.has(track.id))) {
        const fresh = await api.library(0, filter, sort, libraryId)
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
  return <>
    <div className="toolbar"><label className="filter-field"><Icon name="search" /><input value={filter} onChange={(event) => { requestGeneration.current++; setLoadingMore(false); setFilter(event.target.value) }} placeholder="搜索整个 NAS 曲库" /></label><select className="library-filter" aria-label="按音频库筛选" value={libraryId} onChange={(event) => { requestGeneration.current++; setLoadingMore(false); setLibraryId(event.target.value) }}><option value="">全部音频库</option>{libraries.map((library) => <option key={library.id} value={library.id}>{library.name}{library.status === 'offline' ? '（离线）' : ''}</option>)}</select><div className="toolbar-actions">{selected.length > 0 && <button className="primary-button" onClick={() => { const chosen = tracks.filter((track) => selected.includes(track.id)); if (chosen[0]) onPlay(chosen[0], chosen) }}>播放选中（{selected.length}）</button>}<button className="secondary-button" onClick={() => void scan()}><Icon name="refresh" />重新扫描</button></div></div>
    <section className="track-section"><div className="section-heading"><div><h2>{filter ? `${total} 首匹配` : `${total} 首音乐`}</h2><p>已加载 {tracks.length} / {total} 首 · 筛选和排序覆盖所选音频库</p></div>{tracks[0] && <button className="round-play" onClick={() => onPlay(tracks[0], tracks)} aria-label="播放当前已加载曲目"><Icon name="play" /></button>}</div><TrackList tracks={tracks} favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} selectedIds={selected} onSelection={setSelected} sort={sort} onSort={(value) => { requestGeneration.current++; setLoadingMore(false); setSort(value) }} virtualized trailingAction={(track) => { const owner = libraries.find((item) => item.id === track.libraryId); return owner && (!owner.enabled || owner.readOnly || owner.status !== 'online') ? null : <button className="icon-button" onClick={() => void remove(track)} aria-label={`将 ${track.title} 移入回收区`}><Icon name="trash" /></button> }} />{tracks.length < total && <button className="load-more secondary-button" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? '正在加载…' : `继续加载（剩余 ${total - tracks.length} 首）`}</button>}</section>
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
  if (selected) return <>
    <button className="back-button" onClick={() => setSelected(null)}>← 返回歌单</button>
    <TrackSection title={selected.name} subtitle={`${selected.tracks.length} 首 · 全局共享`} tracks={selected.tracks} favorites={[]} onPlay={onPlay} />
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
    <section className="room-intro"><div><h2>让不同设备连接的音响一起播放</h2><p>同步由 Sendspin 负责。每台设备需主动加入并启用声音，蓝牙链路可用静态延迟校准。</p></div><span className="latency-readout">同步误差 {Math.round(room.serverOffset)} ms</span></section>
    <form className="inline-create" onSubmit={create}><input value={name} onChange={(event) => setName(event.target.value)} aria-label="房间名称" /><button className="primary-button">创建并加入</button></form>
    <div className="room-list">{rooms.map((item) => <div key={item.id} className={`room-row ${room.roomId === item.id ? 'active' : ''}`}><span className="speaker-glyph"><Icon name="speaker" /></span><div><strong>{item.name}</strong><small>{item.memberCount} 台设备在线</small></div><span className="room-actions">{room.roomId === item.id ? <button className="secondary-button" onClick={room.leave}>离开</button> : <button className="primary-button" onClick={() => void join(item.id)}>加入并启用声音</button>}<button className="icon-button danger-button" onClick={() => void remove(item)} aria-label={`删除房间 ${item.name}`} title="删除房间"><Icon name="trash" /></button></span></div>)}</div>
    <details className="delay-calibration"><summary>音响固定延迟校准（高级）</summary><p>Sendspin 已自动动态校准网络与浏览器时钟；这里只补偿蓝牙音响或外接声卡自身的固定缓冲，通常保持 0 ms。</p><label className="delay-control"><span>固定输出补偿 <b>{room.deviceDelay} ms</b></span><input type="range" min="0" max="5000" step="10" value={room.deviceDelay} onChange={(event) => room.setDeviceDelay(Number(event.target.value))} /></label></details>
  </>
}

function SettingsPage({ libraries, sources, downloads, theme, onTheme, onRefresh, onNotice }: { libraries: MusicLibrary[]; sources: SourceConfig[]; downloads: DownloadJob[]; theme: Theme; onTheme: (value: Theme) => void; onRefresh: () => Promise<void>; onNotice: (value: string) => void }) {
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
  return <div className="settings-layout">
    <section className="settings-section"><h2>外观</h2><p>选择浅色、深色或适合 OLED 的纯黑主题。</p><div className="theme-options" role="group" aria-label="界面主题">{(['light', 'dark', 'black'] as Theme[]).map((value) => <button key={value} className={theme === value ? 'active' : ''} onClick={() => onTheme(value)} aria-pressed={theme === value}>{({ light: '浅色', dark: '深色', black: '纯黑' })[value]}</button>)}</div></section>
    <section className="settings-section libraries-section"><h2>音频库</h2><p>统一管理 NAS、USB 硬盘和网络挂载。设备掉线时保留曲库索引，不会将音乐误判为已删除。</p><form className="settings-form library-create" onSubmit={addLibrary}><label>名称<input required value={libraryForm.name} onChange={(event) => setLibraryForm({ ...libraryForm, name: event.target.value })} placeholder="例如：客厅 USB 硬盘" /></label><label>容器内路径<input required value={libraryForm.path} onChange={(event) => setLibraryForm({ ...libraryForm, path: event.target.value })} placeholder="/libraries/living-room" /></label><label>设备类型<select value={libraryForm.deviceType} onChange={(event) => setLibraryForm({ ...libraryForm, deviceType: event.target.value as MusicLibraryInput['deviceType'] })}><option value="local">NAS 本地</option><option value="usb">USB 设备</option><option value="network">网络挂载</option><option value="cloud">云盘挂载</option></select></label><label className="check-label"><input type="checkbox" checked={libraryForm.readOnly} onChange={(event) => setLibraryForm({ ...libraryForm, readOnly: event.target.checked, downloadTarget: event.target.checked ? false : libraryForm.downloadTarget })} />只读保护</label><label className="check-label"><input type="checkbox" disabled={libraryForm.readOnly} checked={libraryForm.downloadTarget} onChange={(event) => setLibraryForm({ ...libraryForm, downloadTarget: event.target.checked })} />设为在线下载目标</label><button className="primary-button">添加音频库</button></form><div className="library-list">{libraries.map((library) => <LibraryEditor key={library.id} library={library} onRefresh={onRefresh} onNotice={onNotice} />)}</div></section>
    <section className="settings-section"><h2>音乐源</h2><p>密钥只保存在 NAS，页面只显示末四位。</p><form className="settings-form" onSubmit={save}><label>名称<input required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></label><label>API 地址<input required type="url" value={form.apiUrl} onChange={(event) => setForm({ ...form, apiUrl: event.target.value })} /></label><label>API 密钥<input required type="password" value={form.apiKey} onChange={(event) => setForm({ ...form, apiKey: event.target.value })} /></label><button className="primary-button">保存音乐源</button></form>{sources.map((source) => <div className="source-row" key={source.id}><span><strong>{source.name}</strong><small>{source.apiUrl}</small></span><code>{source.apiKeyMasked}</code></div>)}</section>
    <section className="settings-section"><h2>下载任务</h2><p>完成后自动扫描并进入 NAS 曲库；失败任务可以重试。</p>{downloads.length ? downloads.map((job) => <div className="download-row" key={job.id}><span><strong>{job.title}</strong><small>{job.artist}</small></span>{job.status === 'failed' ? <button className="secondary-button" onClick={async () => { try { await api.retryDownload(job.id); await onRefresh() } catch (error) { onNotice(readError(error)) } }}>重试</button> : <StatusBadge status={job.status} />}</div>) : <EmptyState compact title="暂无下载" body="在线搜索歌曲后可以加入下载队列。" />}</section>
    <section className="settings-section"><h2>HTTP 模式</h2><p>当前局域网入口以普通 Web 运行。切换受信任 HTTPS 后即可验收完整 PWA 安装与音频输出设备选择。</p></section>
  </div>
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

function StatusBadge({ status }: { status: string }) {
  const labels: Record<string, string> = { queued: '等待中', downloading: '下载中', completed: '已完成', failed: '失败' }
  return <span className={`status-badge ${status}`}>{labels[status] ?? status}</span>
}

function readError(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}
