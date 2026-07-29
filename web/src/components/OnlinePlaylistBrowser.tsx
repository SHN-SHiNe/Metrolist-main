import { useEffect, useId, useRef, useState } from 'react'
import { api } from '../api'
import { collectOnlinePlaylistTracks, formatPlayCount, normalizedPage, ONLINE_PLAYLIST_DETAIL_LIMIT } from '../onlinePlaylists'
import type { OnlinePlaylistDetailResponse, OnlinePlaylistSummary, OnlineTrack, Track } from '../types'
import { AlbumArt, EmptyState, TrackList } from './TrackList'
import { Icon } from './Icon'

const SEARCH_LIMIT = 20
const SEARCH_DEBOUNCE_MS = 250

type LoadingStatus = 'idle' | 'loading' | 'ready' | 'error'

export type OnlinePlaylistBrowserProps = {
  query: string
  source: string
  onPlay: (track: Track, queue: Track[]) => void
  onNotice: (value: string) => void
}

export function OnlinePlaylistBrowser({ query, source, onPlay, onNotice }: OnlinePlaylistBrowserProps) {
  const headingId = useId()
  const normalizedQuery = query.trim()
  const [searchPage, setSearchPage] = useState(1)
  const [searchRetry, setSearchRetry] = useState(0)
  const [searchStatus, setSearchStatus] = useState<LoadingStatus>('idle')
  const [searchError, setSearchError] = useState('')
  const [searchResult, setSearchResult] = useState<Awaited<ReturnType<typeof api.searchPlaylists>> | null>(null)
  const [selected, setSelected] = useState<OnlinePlaylistSummary | null>(null)
  const [detailPage, setDetailPage] = useState(1)
  const [detailRetry, setDetailRetry] = useState(0)
  const [detailStatus, setDetailStatus] = useState<LoadingStatus>('idle')
  const [detailError, setDetailError] = useState('')
  const [detail, setDetail] = useState<OnlinePlaylistDetailResponse | null>(null)
  const [completeTracks, setCompleteTracks] = useState<OnlineTrack[] | null>(null)
  const [wholeAction, setWholeAction] = useState<'play' | 'download' | null>(null)
  const [downloadingIds, setDownloadingIds] = useState<Set<string>>(() => new Set())
  const [operationMessage, setOperationMessage] = useState('')
  const selectedKeyRef = useRef('')

  useEffect(() => {
    setSearchPage(1)
    setSelected(null)
    setSearchResult(null)
    setSearchStatus(normalizedQuery ? 'loading' : 'idle')
  }, [normalizedQuery, source])

  useEffect(() => {
    if (!normalizedQuery) {
      setSearchStatus('idle')
      setSearchResult(null)
      return
    }

    const controller = new AbortController()
    const timeout = window.setTimeout(() => {
      setSearchStatus('loading')
      setSearchError('')
      void api.searchPlaylists(normalizedQuery, source, searchPage, SEARCH_LIMIT, controller.signal).then((response) => {
        setSearchResult(response)
        setSearchStatus('ready')
      }).catch((error: unknown) => {
        if (isAbortError(error)) return
        setSearchError(readError(error))
        setSearchStatus('error')
      })
    }, SEARCH_DEBOUNCE_MS)

    return () => {
      window.clearTimeout(timeout)
      controller.abort()
    }
  }, [normalizedQuery, searchPage, searchRetry, source])

  useEffect(() => {
    const selectedKey = selected ? `${selected.source}:${selected.id}` : ''
    selectedKeyRef.current = selectedKey
    setCompleteTracks(null)
    setOperationMessage('')
    if (!selected) {
      setDetail(null)
      setDetailStatus('idle')
      return
    }

    const controller = new AbortController()
    setDetail(null)
    setDetailStatus('loading')
    setDetailError('')
    void api.onlinePlaylistDetail(selected.id, selected.source, detailPage, ONLINE_PLAYLIST_DETAIL_LIMIT, controller.signal).then((response) => {
      if (selectedKeyRef.current !== selectedKey) return
      setDetail(response)
      setDetailStatus('ready')
    }).catch((error: unknown) => {
      if (isAbortError(error) || selectedKeyRef.current !== selectedKey) return
      setDetailError(readError(error))
      setDetailStatus('error')
    })

    return () => controller.abort()
  }, [detailPage, detailRetry, selected])

  const openPlaylist = (item: OnlinePlaylistSummary) => {
    setDetailPage(1)
    setSelected(item)
  }

  const downloadTrack = async (track: OnlineTrack) => {
    if (downloadingIds.has(track.id) || wholeAction === 'download') return
    setDownloadingIds((current) => new Set(current).add(track.id))
    try {
      await api.download(track)
      onNotice(`《${track.title}》已加入 NAS 下载队列`)
    } catch (error) {
      onNotice(`《${track.title}》下载入队失败：${readError(error)}`)
    } finally {
      setDownloadingIds((current) => {
        const next = new Set(current)
        next.delete(track.id)
        return next
      })
    }
  }

  const loadCompleteTracks = async () => {
    if (!detail || !selected) return []
    if (completeTracks) return completeTracks
    const selectedKey = `${selected.source}:${selected.id}`
    const tracks = await collectOnlinePlaylistTracks(detail, (page) => api.onlinePlaylistDetail(
      selected.id,
      selected.source,
      page,
      ONLINE_PLAYLIST_DETAIL_LIMIT,
    ))
    if (selectedKeyRef.current === selectedKey) setCompleteTracks(tracks)
    return tracks
  }

  const playWholePlaylist = async () => {
    if (!detail || !selected || wholeAction) return
    const actionKey = `${selected.source}:${selected.id}`
    setWholeAction('play')
    setOperationMessage('正在读取完整歌单…')
    try {
      const tracks = await loadCompleteTracks()
      if (selectedKeyRef.current !== actionKey) return
      if (!tracks[0]) {
        setOperationMessage('这张歌单暂时没有可播放歌曲')
        return
      }
      onPlay(tracks[0], tracks)
      setOperationMessage(`正在播放整张歌单，共 ${tracks.length} 首`)
    } catch (error) {
      const message = `无法播放整张歌单：${readError(error)}`
      setOperationMessage(message)
      onNotice(message)
    } finally {
      setWholeAction(null)
    }
  }

  const downloadWholePlaylist = async () => {
    if (!detail || wholeAction) return
    setWholeAction('download')
    setOperationMessage('正在读取完整歌单…')
    try {
      const tracks = await loadCompleteTracks()
      if (!tracks.length) {
        setOperationMessage('这张歌单暂时没有可下载歌曲')
        return
      }
      setOperationMessage(`正在将 ${tracks.length} 首加入 NAS 下载队列…`)
      let completed = 0
      let failed = 0
      for (const [index, track] of tracks.entries()) {
        setOperationMessage(`正在加入 NAS 下载队列：${index + 1} / ${tracks.length}`)
        try {
          await api.download(track)
          completed += 1
        } catch {
          failed += 1
        }
      }
      const message = failed
        ? `已将 ${completed} 首加入 NAS 下载队列，${failed} 首入队失败`
        : `已将整张歌单的 ${completed} 首加入 NAS 下载队列`
      setOperationMessage(message)
      onNotice(message)
    } catch (error) {
      const message = `整张下载入 NAS 失败：${readError(error)}`
      setOperationMessage(message)
      onNotice(message)
    } finally {
      setWholeAction(null)
    }
  }

  if (selected) {
    return <section className="online-playlist-browser" aria-labelledby={detailStatus === 'ready' && detail ? headingId : undefined} aria-label={detailStatus === 'ready' && detail ? undefined : `在线歌单 ${selected.name}`} aria-busy={detailStatus === 'loading'}>
      <button className="back-button" onClick={() => setSelected(null)}><Icon name="back" />返回在线歌单</button>
      {detailStatus === 'loading' && <LoadingState label="正在读取歌单详情" />}
      {detailStatus === 'error' && <ErrorState message={detailError} onRetry={() => setDetailRetry((value) => value + 1)} />}
      {detailStatus === 'ready' && detail && <>
        <section className="playlist-detail-hero">
          <div className="playlist-hero-art"><AlbumArt title={detail.name} artworkUrl={detail.artworkUrl} /></div>
          <div className="playlist-hero-copy">
            <span>{sourceLabel(detail.source)}在线歌单</span>
            <h2 id={headingId}>{detail.name}</h2>
            <p>{detail.author || '未知创建者'} · {detail.total} 首</p>
            {detail.description && <p>{detail.description}</p>}
            <div className="playlist-hero-actions">
              <button className="playlist-main-play" disabled={!detail.total || wholeAction !== null} onClick={() => void playWholePlaylist()} aria-label={`播放整张歌单 ${detail.name}`}><Icon name="play" />{wholeAction === 'play' ? '读取中…' : '播放整张'}</button>
              <button className="secondary-button" disabled={!detail.total || wholeAction !== null} onClick={() => void downloadWholePlaylist()}><Icon name="download" />{wholeAction === 'download' ? '加入队列中…' : '整张下载入 NAS'}</button>
            </div>
          </div>
        </section>
        <div className="search-capability-note" role="note"><strong>下载后进入 NAS 曲库</strong><span>这里只会将整张歌单加入 NAS 下载队列；共享歌单关联要等本地索引完成后再处理，不会伪装成已经导入。</span></div>
        {operationMessage && <p role="status" aria-live="polite">{operationMessage}</p>}
        <section className="track-section" aria-label={`${detail.name}的歌曲`}>
          <div className="section-heading"><div><h2>歌曲</h2><p>第 {detail.page} / {Math.max(1, detail.allPages)} 页 · 当前 {detail.tracks.length} 首</p></div></div>
          {detail.tracks.length ? <TrackList
            tracks={detail.tracks}
            favorites={[]}
            onPlay={onPlay}
            trailingAction={(track) => <button className="icon-button" disabled={downloadingIds.has(track.id) || wholeAction === 'download'} onClick={() => void downloadTrack(track as OnlineTrack)} title="下载到 NAS" aria-label={`下载 ${track.title} 到 NAS`}><Icon name="download" /></button>}
          /> : <EmptyState title="这张歌单没有歌曲" body="音源暂时没有返回可播放曲目，可以稍后重试或切换其他歌单。" />}
          <Pagination page={detail.page} allPages={detail.allPages} label="歌单歌曲分页" onChange={setDetailPage} />
        </section>
      </>}
    </section>
  }

  if (!normalizedQuery) {
    return <EmptyState title="搜索国内歌单" body="输入歌单名称、风格或场景，从网易云、QQ、酷狗、酷我等来源查找真实歌单。" />
  }

  return <section className="online-playlist-browser" aria-labelledby={headingId} aria-busy={searchStatus === 'loading'}>
    <div className="section-heading"><div><h2 id={headingId}>“{normalizedQuery}”的在线歌单</h2><p>{searchResult ? `找到 ${searchResult.total} 张 · ${sourceLabel(searchResult.source)}` : '正在连接国内音乐源'}</p></div></div>
    {searchStatus === 'loading' && <LoadingState label="正在搜索在线歌单" />}
    {searchStatus === 'error' && <ErrorState message={searchError} onRetry={() => setSearchRetry((value) => value + 1)} />}
    {searchStatus === 'ready' && searchResult && <>
      {searchResult.items.length ? <div className="playlist-grid" role="list" aria-label="在线歌单搜索结果">{searchResult.items.map((item) => <article key={`${item.source}:${item.id}`} role="listitem"><button className="playlist-tile" onClick={() => openPlaylist(item)} aria-label={`打开歌单 ${item.name}`}><AlbumArt title={item.name} artworkUrl={item.artworkUrl} /><strong>{item.name}</strong><span>{item.author || sourceLabel(item.source)} · {formatPlayCount(item.playCount)}</span></button></article>)}</div> : <EmptyState title="没有找到匹配歌单" body="可以换一个关键词，或切换网易云、QQ、酷狗、酷我等音源再试。" />}
      <Pagination page={searchResult.page} allPages={searchResult.allPages} label="在线歌单搜索分页" onChange={setSearchPage} />
    </>}
  </section>
}

function Pagination({ page, allPages, label, onChange }: { page: number; allPages: number; label: string; onChange: (page: number) => void }) {
  const current = normalizedPage(page, allPages)
  const total = Math.max(1, allPages)
  if (total <= 1) return null
  return <nav className="toolbar online-playlist-pagination" aria-label={label}>
    <button className="secondary-button" disabled={current <= 1} onClick={() => onChange(current - 1)}>上一页</button>
    <span aria-live="polite">第 {current} / {total} 页</span>
    <button className="secondary-button" disabled={current >= total} onClick={() => onChange(current + 1)}>下一页</button>
  </nav>
}

function LoadingState({ label }: { label: string }) {
  return <div className="loading-rows" role="status" aria-live="polite" aria-label={label}><span /><span /><span /><span /><span /></div>
}

function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return <div className="search-capability-note" role="alert"><strong>暂时无法读取在线歌单</strong><span>{message}</span><button className="secondary-button" onClick={onRetry}><Icon name="refresh" />重试</button></div>
}

function sourceLabel(source: string): string {
  return ({ all: '聚合', netease: '网易云', wy: '网易云', qq: 'QQ', tx: 'QQ', kugou: '酷狗', kg: '酷狗', kuwo: '酷我', kw: '酷我', migu: '咪咕', mg: '咪咕' } as Record<string, string>)[source.toLowerCase()] ?? source
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function readError(error: unknown): string {
  return error instanceof Error ? error.message : '未知错误'
}
