import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react'
import { api } from './api'
import type { DownloadJob, HistoryEntry, PlaylistDetail, PlaylistSummary, RoomSummary, SourceConfig, Track } from './types'
import { usePlayer } from './usePlayer'
import { useRoomSync } from './useRoomSync'

type Section = 'home' | 'search' | 'library' | 'favorites' | 'playlists' | 'rooms' | 'settings'
type Theme = 'light' | 'dark' | 'black'

const navItems: { id: Section; label: string; icon: IconName }[] = [
  { id: 'home', label: '首页', icon: 'home' },
  { id: 'search', label: '搜索', icon: 'search' },
  { id: 'library', label: 'NAS 曲库', icon: 'library' },
  { id: 'favorites', label: '收藏', icon: 'heart' },
  { id: 'playlists', label: '歌单', icon: 'playlist' },
  { id: 'rooms', label: '同步房间', icon: 'room' },
]

export default function App() {
  const [section, setSection] = useState<Section>(() => (location.hash.slice(1) as Section) || 'home')
  const [library, setLibrary] = useState<Track[]>([])
  const [favorites, setFavorites] = useState<Track[]>([])
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const [playlists, setPlaylists] = useState<PlaylistSummary[]>([])
  const [selectedPlaylist, setSelectedPlaylist] = useState<PlaylistDetail | null>(null)
  const [rooms, setRooms] = useState<RoomSummary[]>([])
  const [sources, setSources] = useState<SourceConfig[]>([])
  const [downloads, setDownloads] = useState<DownloadJob[]>([])
  const [searchResults, setSearchResults] = useState<Track[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState<string | null>(null)
  const [nowPlayingOpen, setNowPlayingOpen] = useState(false)
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem('shine-theme') as Theme) || 'dark')
  const [queueOpen, setQueueOpen] = useState(true)
  const player = usePlayer()
  const allTracks = useMemo(() => [...library, ...searchResults].filter((track, index, array) => array.findIndex((item) => item.id === track.id) === index), [library, searchResults])
  const room = useRoomSync(player, allTracks)

  const refresh = useCallback(async () => {
    try {
      const [libraryPage, favoriteTracks, historyEntries, playlistItems, roomItems, sourceItems, downloadItems] = await Promise.all([
        api.library(), api.favorites(), api.history(), api.playlists(), api.rooms(), api.sources(), api.downloads(),
      ])
      setLibrary(libraryPage.items)
      setFavorites(favoriteTracks)
      setHistory(historyEntries)
      setPlaylists(playlistItems)
      setRooms(roomItems)
      setSources(sourceItems)
      setDownloads(downloadItems)
    } catch (error) {
      setNotice(readError(error))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('shine-theme', theme)
  }, [theme])
  useEffect(() => {
    const onHash = () => setSection((location.hash.slice(1) as Section) || 'home')
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  const navigate = (next: Section) => {
    location.hash = next
    setSection(next)
  }

  const play = (track: Track, queue: Track[]) => {
    if (room.status === 'joined') {
      room.command({ queue: queue.map((item) => item.id), currentTrackId: track.id, positionMs: 0, playing: true })
    } else player.playTrack(track, queue)
  }

  const togglePlayback = () => {
    if (room.status === 'joined' && player.current) {
      room.command({ currentTrackId: player.current.id, positionMs: Math.round(player.position * 1000), playing: !player.playing })
    } else player.toggle()
  }

  const skipPlayback = useCallback((delta: -1 | 1) => {
    if (room.status !== 'joined' || !player.current || player.queue.length === 0) {
      if (delta === 1) player.next(); else player.previous()
      return
    }
    if (delta === -1 && player.position > 5) {
      room.command({ currentTrackId: player.current.id, positionMs: 0, playing: player.playing })
      return
    }
    const nextIndex = (player.index + delta + player.queue.length) % player.queue.length
    room.command({
      queue: player.queue.map((item) => item.id),
      currentTrackId: player.queue[nextIndex].id,
      positionMs: 0,
      playing: true,
    })
  }, [player, room])

  const seekPlayback = useCallback((seconds: number) => {
    if (room.status === 'joined' && player.current) {
      room.command({ currentTrackId: player.current.id, positionMs: Math.round(seconds * 1000), playing: player.playing })
    } else player.seek(seconds)
  }, [player, room])

  useEffect(() => {
    const onEnded = () => skipPlayback(1)
    player.audio.addEventListener('ended', onEnded)
    return () => player.audio.removeEventListener('ended', onEnded)
  }, [player.audio, skipPlayback])

  const toggleFavorite = async (track: Track) => {
    const favorite = !favorites.some((item) => item.id === track.id)
    await api.setFavorite(track.id, favorite)
    await refresh()
  }

  const title = navItems.find((item) => item.id === section)?.label ?? 'SHiNe MUSIC'

  return (
    <div className={`app-shell ${queueOpen ? '' : 'queue-closed'}`}>
      <aside className="sidebar" aria-label="主导航">
        <Brand />
        <nav className="nav-list">
          {navItems.map((item) => <NavButton key={item.id} item={item} active={section === item.id} onClick={() => navigate(item.id)} />)}
        </nav>
        <button className="nav-settings" onClick={() => navigate('settings')} aria-current={section === 'settings' ? 'page' : undefined}>
          <Icon name="settings" /><span>设置</span>
        </button>
      </aside>

      <main className="main-content" id="main-content">
        <header className="topbar">
          <div><p className="eyeline">SHiNe Music · 家庭 NAS</p><h1>{title}</h1></div>
          <div className="topbar-actions"><div className={`connection ${room.status}`} title={`时钟偏移 ${Math.round(room.serverOffset)}ms`}><span className="status-dot" />{room.status === 'joined' ? `${room.members} 台设备同步中` : '独立播放'}</div><button className="queue-toggle secondary-button" onClick={() => setQueueOpen((value) => !value)} aria-expanded={queueOpen} aria-controls="play-queue">{queueOpen ? '收起队列' : '展开队列'}</button></div>
        </header>
        {notice && <div className="notice" role="status"><span>{notice}</span><button onClick={() => setNotice(null)} aria-label="关闭提示">×</button></div>}
        {loading ? <LoadingRows /> : (
          <div className="page-content">
            {section === 'home' && <HomePage history={history} favorites={favorites} onPlay={play} onNavigate={navigate} />}
            {section === 'search' && <SearchPage query={query} setQuery={setQuery} results={searchResults} setResults={setSearchResults} onPlay={play} onFavorite={toggleFavorite} favorites={favorites} onNotice={setNotice} />}
            {section === 'library' && <LibraryPage tracks={library} favorites={favorites} onPlay={play} onFavorite={toggleFavorite} onRefresh={refresh} onNotice={setNotice} />}
            {section === 'favorites' && <TrackSection title="共享收藏" subtitle="局域网内所有访客看到同一份收藏" tracks={favorites} favorites={favorites} onPlay={play} onFavorite={toggleFavorite} />}
            {section === 'playlists' && <PlaylistsPage playlists={playlists} selected={selectedPlaylist} setSelected={setSelectedPlaylist} library={library} onPlay={play} onRefresh={refresh} onNotice={setNotice} />}
            {section === 'rooms' && <RoomsPage rooms={rooms} room={room} onRefresh={refresh} onNotice={setNotice} />}
            {section === 'settings' && <SettingsPage sources={sources} downloads={downloads} theme={theme} onTheme={setTheme} onRefresh={refresh} onNotice={setNotice} />}
          </div>
        )}
      </main>

      <aside id="play-queue" className={`queue-panel ${queueOpen ? '' : 'closed'}`} aria-label="播放队列">
        <div className="panel-heading"><h2>接下来播放</h2><span>{player.queue.length} 首</span></div>
        {player.queue.length ? player.queue.map((track, index) => (
          <button key={`${track.id}-${index}`} className={`queue-item ${index === player.index ? 'active' : ''}`} onClick={() => play(track, player.queue)}>
            <span className="queue-number">{index === player.index && player.playing ? '▶' : index + 1}</span>
            <span><strong>{track.title}</strong><small>{track.artist}</small></span>
          </button>
        )) : <EmptyState compact title="队列还是空的" body="从曲库或搜索结果中选择一首歌。" />}
      </aside>

      <PlayerBar player={player} room={room} onToggle={togglePlayback} onPrevious={() => skipPlayback(-1)} onNext={() => skipPlayback(1)} onSeek={seekPlayback} onOpen={() => setNowPlayingOpen(true)} />
      <nav className="mobile-nav" aria-label="移动端主导航">
        {navItems.map((item) => <NavButton key={item.id} item={item} active={section === item.id} onClick={() => navigate(item.id)} />)}
        <NavButton item={{ id: 'settings', label: '设置', icon: 'settings' }} active={section === 'settings'} onClick={() => navigate('settings')} />
      </nav>
      {nowPlayingOpen && <NowPlaying player={player} onToggle={togglePlayback} onPrevious={() => skipPlayback(-1)} onNext={() => skipPlayback(1)} onSeek={seekPlayback} onClose={() => setNowPlayingOpen(false)} />}
    </div>
  )
}

function HomePage({ history, favorites, onPlay, onNavigate }: { history: HistoryEntry[]; favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onNavigate: (section: Section) => void }) {
  const recent = history.slice(0, 8).map((entry) => entry.track)
  return <>
    <section className="hero-strip">
      <div><span className="hero-mark">局域网音乐室</span><h2>音乐在 NAS，播放在每一台设备。</h2><p>共享整理，独立聆听；需要时，一键加入同步房间。</p></div>
      <button className="primary-button" onClick={() => onNavigate('library')}><Icon name="play" />打开曲库</button>
    </section>
    <TrackSection title="最近播放" subtitle="来自所有访客的共享历史" tracks={recent} favorites={favorites} onPlay={onPlay} />
    <AlbumRow title="最常回来的收藏" tracks={favorites.slice(0, 6)} onPlay={onPlay} />
  </>
}

function SearchPage({ query, setQuery, results, setResults, onPlay, onFavorite, favorites, onNotice }: {
  query: string; setQuery: (value: string) => void; results: Track[]; setResults: (value: Track[]) => void
  onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void; favorites: Track[]; onNotice: (value: string) => void
}) {
  const [searching, setSearching] = useState(false)
  const search = async (event: FormEvent) => {
    event.preventDefault()
    if (!query.trim()) return
    setSearching(true)
    try { setResults((await api.search(query)).items) } catch (error) { onNotice(readError(error)) } finally { setSearching(false) }
  }
  const download = async (track: Track) => {
    try { await api.download(track); onNotice(`《${track.title}》已加入 NAS 下载队列`) } catch (error) { onNotice(readError(error)) }
  }
  return <>
    <form className="search-box" onSubmit={search} role="search">
      <Icon name="search" /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索歌曲、歌手或专辑" aria-label="在线搜索" />
      <button className="primary-button" disabled={searching}>{searching ? '搜索中…' : '搜索'}</button>
    </form>
    {results.length ? <TrackList tracks={results} favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} trailingAction={(track) => <button className="icon-button" onClick={() => void download(track)} title="下载到 NAS"><Icon name="download" /></button>} /> : <EmptyState title="从五大音源开始搜索" body="搜索结果可以直接播放，也可以下载到 NAS 永久保存。" />}
  </>
}

function LibraryPage({ tracks, favorites, onPlay, onFavorite, onRefresh, onNotice }: { tracks: Track[]; favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void; onRefresh: () => Promise<void>; onNotice: (value: string) => void }) {
  const [filter, setFilter] = useState('')
  const [sort, setSort] = useState<'title' | 'artist' | 'album'>('artist')
  const [selected, setSelected] = useState<string[]>([])
  const visible = tracks.filter((track) => `${track.title} ${track.artist} ${track.album}`.toLowerCase().includes(filter.toLowerCase()))
    .sort((left, right) => left[sort].localeCompare(right[sort], 'zh-CN'))
  const scan = async () => {
    try { await api.scan(); await onRefresh(); onNotice('NAS 曲库扫描完成') } catch (error) { onNotice(readError(error)) }
  }
  const remove = async (track: Track) => {
    if (!window.confirm(`将《${track.title}》移入 NAS 回收区？`)) return
    try { await api.deleteTrack(track.id); setSelected((ids) => ids.filter((id) => id !== track.id)); await onRefresh(); onNotice('音乐已移入服务端回收区') } catch (error) { onNotice(readError(error)) }
  }
  return <>
    <div className="toolbar"><label className="filter-field"><Icon name="search" /><input value={filter} onChange={(event) => setFilter(event.target.value)} placeholder="筛选当前曲库" /></label><div className="toolbar-actions">{selected.length > 0 && <button className="primary-button" onClick={() => { const chosen = visible.filter((track) => selected.includes(track.id)); if (chosen[0]) onPlay(chosen[0], chosen) }}>播放选中（{selected.length}）</button>}<button className="secondary-button" onClick={() => void scan()}><Icon name="refresh" />重新扫描</button></div></div>
    <section className="track-section"><div className="section-heading"><div><h2>{visible.length} 首音乐</h2><p>扫描自 NAS 挂载目录 · 可排序和多选</p></div>{visible[0] && <button className="round-play" onClick={() => onPlay(visible[0], visible)} aria-label="播放当前曲库"><Icon name="play" /></button>}</div><TrackList tracks={visible} favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} selectedIds={selected} onSelection={setSelected} sort={sort} onSort={setSort} trailingAction={(track) => <button className="icon-button" onClick={() => void remove(track)} aria-label={`将 ${track.title} 移入回收区`}><Icon name="trash" /></button>} /></section>
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
    try { const created = await api.createRoom(name); await onRefresh(); room.join(created.id) } catch (error) { onNotice(readError(error)) }
  }
  return <>
    <section className="room-intro"><div><h2>让不同设备连接的音响一起播放</h2><p>每台设备先打开本页并主动加入房间。蓝牙链路可以用延迟补偿校准。</p></div><span className="latency-readout">服务器时钟 {Math.round(room.serverOffset)} ms</span></section>
    <form className="inline-create" onSubmit={create}><input value={name} onChange={(event) => setName(event.target.value)} aria-label="房间名称" /><button className="primary-button">创建并加入</button></form>
    <div className="room-list">{rooms.map((item) => <div key={item.id} className={`room-row ${room.roomId === item.id ? 'active' : ''}`}><span className="speaker-glyph"><Icon name="speaker" /></span><div><strong>{item.name}</strong><small>{item.memberCount} 台设备在线</small></div>{room.roomId === item.id ? <button className="secondary-button" onClick={room.leave}>离开</button> : <button className="primary-button" onClick={() => room.join(item.id)}>加入并启用声音</button>}</div>)}</div>
    <label className="delay-control"><span>本设备延迟补偿 <b>{room.deviceDelay} ms</b></span><input type="range" min="-500" max="1500" step="10" value={room.deviceDelay} onChange={(event) => room.setDeviceDelay(Number(event.target.value))} /></label>
  </>
}

function SettingsPage({ sources, downloads, theme, onTheme, onRefresh, onNotice }: { sources: SourceConfig[]; downloads: DownloadJob[]; theme: Theme; onTheme: (value: Theme) => void; onRefresh: () => Promise<void>; onNotice: (value: string) => void }) {
  const [form, setForm] = useState({ name: '', apiUrl: '', apiKey: '' })
  const save = async (event: FormEvent) => {
    event.preventDefault()
    try { await api.createSource(form); setForm({ name: '', apiUrl: '', apiKey: '' }); await onRefresh(); onNotice('音乐源已安全保存到 NAS') } catch (error) { onNotice(readError(error)) }
  }
  return <div className="settings-layout">
    <section className="settings-section"><h2>外观</h2><p>选择浅色、深色或适合 OLED 的纯黑主题。</p><div className="theme-options" role="group" aria-label="界面主题">{(['light', 'dark', 'black'] as Theme[]).map((value) => <button key={value} className={theme === value ? 'active' : ''} onClick={() => onTheme(value)} aria-pressed={theme === value}>{({ light: '浅色', dark: '深色', black: '纯黑' })[value]}</button>)}</div></section>
    <section className="settings-section"><h2>音乐源</h2><p>密钥只保存在 NAS，页面只显示末四位。</p><form className="settings-form" onSubmit={save}><label>名称<input required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></label><label>API 地址<input required type="url" value={form.apiUrl} onChange={(event) => setForm({ ...form, apiUrl: event.target.value })} /></label><label>API 密钥<input required type="password" value={form.apiKey} onChange={(event) => setForm({ ...form, apiKey: event.target.value })} /></label><button className="primary-button">保存音乐源</button></form>{sources.map((source) => <div className="source-row" key={source.id}><span><strong>{source.name}</strong><small>{source.apiUrl}</small></span><code>{source.apiKeyMasked}</code></div>)}</section>
    <section className="settings-section"><h2>下载任务</h2><p>完成后自动扫描并进入 NAS 曲库；失败任务可以重试。</p>{downloads.length ? downloads.map((job) => <div className="download-row" key={job.id}><span><strong>{job.title}</strong><small>{job.artist}</small></span>{job.status === 'failed' ? <button className="secondary-button" onClick={async () => { try { await api.retryDownload(job.id); await onRefresh() } catch (error) { onNotice(readError(error)) } }}>重试</button> : <StatusBadge status={job.status} />}</div>) : <EmptyState compact title="暂无下载" body="在线搜索歌曲后可以加入下载队列。" />}</section>
    <section className="settings-section"><h2>HTTP 模式</h2><p>当前局域网入口以普通 Web 运行。切换受信任 HTTPS 后即可验收完整 PWA 安装与音频输出设备选择。</p></section>
  </div>
}

function TrackSection({ title, subtitle, tracks, favorites, onPlay, onFavorite }: { title: string; subtitle: string; tracks: Track[]; favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite?: (track: Track) => void }) {
  return <section className="track-section"><div className="section-heading"><div><h2>{title}</h2><p>{subtitle}</p></div>{tracks.length > 0 && <button className="round-play" onClick={() => onPlay(tracks[0], tracks)} aria-label={`播放${title}`}><Icon name="play" /></button>}</div>{tracks.length ? <TrackList tracks={tracks} favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} /> : <EmptyState title="这里还没有音乐" body="扫描 NAS、在线下载或继续收藏后会显示在这里。" />}</section>
}

function TrackList({ tracks, favorites, onPlay, onFavorite, trailingAction, selectedIds, onSelection, sort, onSort }: { tracks: Track[]; favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite?: (track: Track) => void; trailingAction?: (track: Track) => React.ReactNode; selectedIds?: string[]; onSelection?: (ids: string[]) => void; sort?: 'title' | 'artist' | 'album'; onSort?: (value: 'title' | 'artist' | 'album') => void }) {
  const selectable = Boolean(onSelection && selectedIds)
  const toggle = (id: string) => onSelection?.(selectedIds?.includes(id) ? selectedIds.filter((value) => value !== id) : [...(selectedIds ?? []), id])
  return <div className="track-list" role="list"><div className="track-header"><span>{selectable ? <label className="track-select"><input type="checkbox" aria-label="全选当前曲目" checked={selectedIds?.length === tracks.length && tracks.length > 0} onChange={(event) => onSelection?.(event.target.checked ? tracks.map((track) => track.id) : [])} /></label> : '#'}</span><button disabled={!onSort} onClick={() => onSort?.(sort === 'title' ? 'artist' : 'title')}>标题 / 歌手{sort === 'title' ? ' ↑' : sort === 'artist' ? ' ↓' : ''}</button><button disabled={!onSort} onClick={() => onSort?.('album')}>专辑{sort === 'album' ? ' ↑' : ''}</button><span>时长</span><span /></div>{tracks.map((track, index) => {
    const favorite = favorites.some((item) => item.id === track.id)
    return <div className="track-row" role="listitem" key={track.id}>{selectable ? <label className="track-select"><input type="checkbox" checked={selectedIds?.includes(track.id)} onChange={() => toggle(track.id)} aria-label={`选择 ${track.title}`} /></label> : <button className="track-play" onClick={() => onPlay(track, tracks)} aria-label={`播放 ${track.title}`}>{index + 1}<span className="hover-play">▶</span></button>}<button className="track-identity" onClick={() => onPlay(track, tracks)} aria-label={`播放 ${track.title}`}><AlbumArt title={track.title} artworkUrl={track.artworkUrl} small /><span><strong>{track.title}</strong><small>{track.artist}</small></span></button><span className="track-album">{track.album || track.source || '本地音乐'}</span><span className="track-duration">{formatTime(track.durationMs / 1000)}</span><span className="track-actions">{onFavorite && <button className={`icon-button ${favorite ? 'favorite' : ''}`} onClick={() => onFavorite(track)} aria-label={favorite ? '取消收藏' : '收藏'}><Icon name="heart" /></button>}{trailingAction?.(track)}</span></div>
  })}</div>
}

function AlbumRow({ title, tracks, onPlay }: { title: string; tracks: Track[]; onPlay: (track: Track, queue: Track[]) => void }) {
  if (!tracks.length) return null
  return <section><div className="section-heading"><h2>{title}</h2></div><div className="album-row">{tracks.map((track) => <button key={track.id} className="album-item" onClick={() => onPlay(track, tracks)}><AlbumArt title={track.title} artworkUrl={track.artworkUrl} /><strong>{track.title}</strong><span>{track.artist}</span></button>)}</div></section>
}

function PlayerBar({ player, room, onToggle, onPrevious, onNext, onSeek, onOpen }: { player: ReturnType<typeof usePlayer>; room: ReturnType<typeof useRoomSync>; onToggle: () => void; onPrevious: () => void; onNext: () => void; onSeek: (seconds: number) => void; onOpen: () => void }) {
  return <footer className={`player-bar ${player.current ? 'has-track' : ''}`}><button className="player-track" onClick={onOpen} disabled={!player.current}><AlbumArt title={player.current?.title ?? 'SHiNe'} small /><span><strong>{player.current?.title ?? '选择一首歌开始播放'}</strong><small>{player.current?.artist ?? 'SHiNe MUSIC'}</small></span></button><div className="player-center"><div className="transport"><button className="icon-button" onClick={onPrevious} aria-label="上一首"><Icon name="previous" /></button><button className="player-toggle" onClick={onToggle} disabled={!player.current} aria-label={player.playing ? '暂停' : '播放'}><Icon name={player.playing ? 'pause' : 'play'} /></button><button className="icon-button" onClick={onNext} aria-label="下一首"><Icon name="next" /></button></div><div className="progress-row"><span>{formatTime(player.position)}</span><input aria-label="播放进度" type="range" min="0" max={player.duration || 1} step="0.1" value={Math.min(player.position, player.duration || 1)} onChange={(event) => onSeek(Number(event.target.value))} /><span>{formatTime(player.duration)}</span></div></div><div className="player-extras"><span className={`room-pill ${room.status}`}>{room.status === 'joined' ? `${room.members} 台同步` : '本机'}</span><Icon name="volume" /><input aria-label="音量" type="range" min="0" max="1" step="0.01" value={player.volume} onChange={(event) => player.setVolume(Number(event.target.value))} /></div></footer>
}

function NowPlaying({ player, onToggle, onPrevious, onNext, onSeek, onClose }: { player: ReturnType<typeof usePlayer>; onToggle: () => void; onPrevious: () => void; onNext: () => void; onSeek: (seconds: number) => void; onClose: () => void }) {
  return <div className="now-playing" role="dialog" aria-modal="true" aria-label="正在播放"><button className="close-now-playing" onClick={onClose} aria-label="关闭">⌄</button><AlbumArt title={player.current?.title ?? 'SHiNe'} hero /><div className="now-meta"><h2>{player.current?.title}</h2><p>{player.current?.artist}</p></div><input aria-label="播放进度" type="range" min="0" max={player.duration || 1} value={player.position} onChange={(event) => onSeek(Number(event.target.value))} /><div className="now-controls"><button onClick={onPrevious} aria-label="上一首"><Icon name="previous" /></button><button className="player-toggle" onClick={onToggle}><Icon name={player.playing ? 'pause' : 'play'} /></button><button onClick={onNext} aria-label="下一首"><Icon name="next" /></button></div></div>
}

function NavButton({ item, active, onClick }: { item: typeof navItems[number]; active: boolean; onClick: () => void }) { return <button className={`nav-button ${active ? 'active' : ''}`} onClick={onClick} aria-current={active ? 'page' : undefined}><Icon name={item.icon} /><span>{item.label}</span></button> }
function Brand() { return <div className="brand"><span className="brand-icon">♪</span><span><strong>SHiNe</strong><small>家庭音乐</small></span></div> }
function AlbumArt({ title, artworkUrl, small, hero }: { title: string; artworkUrl?: string | null; small?: boolean; hero?: boolean }) { return <span className={`album-art ${small ? 'small' : ''} ${hero ? 'hero' : ''}`} aria-hidden="true">{artworkUrl && <img src={artworkUrl} alt="" onError={(event) => event.currentTarget.remove()} />}<span>♪</span><i>{title.slice(0, 1).toUpperCase()}</i></span> }
function EmptyState({ title, body, compact }: { title: string; body: string; compact?: boolean }) { return <div className={`empty-state ${compact ? 'compact' : ''}`}><span>♫</span><strong>{title}</strong><p>{body}</p></div> }
function LoadingRows() { return <div className="loading-rows" aria-label="加载中">{Array.from({ length: 7 }, (_, index) => <span key={index} />)}</div> }
function StatusBadge({ status }: { status: string }) { const labels: Record<string, string> = { queued: '等待中', downloading: '下载中', completed: '已完成', failed: '失败' }; return <span className={`status-badge ${status}`}>{labels[status] ?? status}</span> }
function formatTime(seconds: number) { if (!Number.isFinite(seconds) || seconds < 0) return '0:00'; return `${Math.floor(seconds / 60)}:${Math.floor(seconds % 60).toString().padStart(2, '0')}` }
function readError(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试' }

type IconName = 'home' | 'search' | 'library' | 'heart' | 'playlist' | 'room' | 'settings' | 'play' | 'pause' | 'previous' | 'next' | 'volume' | 'refresh' | 'download' | 'speaker' | 'trash'
const paths: Record<IconName, string> = {
  home: 'M3 11.5 12 4l9 7.5V20a1 1 0 0 1-1 1h-5v-6H10v6H5a1 1 0 0 1-1-1z', search: 'm21 21-4.4-4.4m2.4-5.1a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z', library: 'M4 5h4v14H4zm6-2h4v16h-4zm6 5h4v11h-4z', heart: 'M20.8 5.7a5.5 5.5 0 0 0-7.8 0L12 6.8l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 22l8.8-8.5a5.5 5.5 0 0 0 0-7.8Z', playlist: 'M4 6h11M4 11h11M4 16h7m7-3v8m-4-4h8', room: 'M4 14a8 8 0 0 1 16 0m-12 0a4 4 0 0 1 8 0m-4 0v.01', settings: 'M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm7.4-3.5 2-1.2-2-3.5-2.2.7a8 8 0 0 0-1.2-.7l-.5-2.3h-4l-.5 2.3a8 8 0 0 0-1.2.7l-2.2-.7-2 3.5 2 1.2v1.4l-2 1.2 2 3.5 2.2-.7 1.2.7.5 2.3h4l.5-2.3 1.2-.7 2.2.7 2-3.5-2-1.2z', play: 'm9 7 9 5-9 5z', pause: 'M8 6h3v12H8zm5 0h3v12h-3z', previous: 'M7 6h2v12H7zm3 6 8-6v12z', next: 'M15 6h2v12h-2zm-1 6-8 6V6z', volume: 'M4 10v4h4l5 4V6l-5 4zm12-1a4 4 0 0 1 0 6m2-8a7 7 0 0 1 0 10', refresh: 'M20 7v5h-5m4-1a7 7 0 1 0 0 4', download: 'M12 3v12m-5-5 5 5 5-5M5 20h14', speaker: 'M5 9h4l5-4v14l-5-4H5zm12 1a3 3 0 0 1 0 4m2-7a7 7 0 0 1 0 10', trash: 'M4 7h16M9 7V4h6v3m3 0-1 14H7L6 7m4 4v6m4-6v6',
}
function Icon({ name }: { name: IconName }) { return <svg viewBox="0 0 24 24" aria-hidden="true"><path d={paths[name]} /></svg> }
