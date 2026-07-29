import type { AdvancedSearchRequest, AdvancedSearchResponse, AnalysisEnqueueResponse, AnalysisSummary, DownloadJob, HistoryEntry, MusicLibrary, MusicLibraryInput, PlaylistDetail, PlaylistSummary, RoomDetail, RoomPlaybackState, RoomSummary, SearchResponse, SimilarTracksResponse, SourceConfig, Track, TrackPage } from './types'
import { LIBRARY_PAGE_SIZE } from './libraryPaging'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { error?: string } | null
    throw new Error(payload?.error ?? `${response.status} ${response.statusText}`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const api = {
  library: (offset = 0, q = '', sort: 'title' | 'artist' | 'album' = 'artist', libraryId = '') => request<TrackPage>(`/api/library?offset=${offset}&limit=${LIBRARY_PAGE_SIZE}&q=${encodeURIComponent(q)}&sort=${sort}&libraryId=${encodeURIComponent(libraryId)}`),
  tracks: (ids: string[]) => request<Track[]>(`/api/tracks?ids=${encodeURIComponent(ids.join(','))}`),
  libraries: () => request<MusicLibrary[]>('/api/libraries'),
  createLibrary: (value: MusicLibraryInput) => request<MusicLibrary>('/api/libraries', { method: 'POST', body: JSON.stringify(value) }),
  updateLibrary: (id: string, value: MusicLibraryInput) => request<MusicLibrary>(`/api/libraries/${id}`, { method: 'PUT', body: JSON.stringify(value) }),
  scanLibrary: (id: string, allowEmpty = false) => request(`/api/libraries/${id}/scan?allowEmpty=${allowEmpty}`, { method: 'POST' }),
  scan: () => request('/api/scans', { method: 'POST' }),
  deleteTrack: (id: string) => request(`/api/library/${id}`, { method: 'DELETE' }),
  similar: (id: string, limit = 20, recentTrackIds: string[] = []) => {
    const query = new URLSearchParams({ limit: String(limit) })
    recentTrackIds.forEach((recent) => query.append('recent', recent))
    return request<SimilarTracksResponse>(`/api/library/${id}/similar?${query}`)
  },
  advancedSearch: (value: AdvancedSearchRequest) => request<AdvancedSearchResponse>('/api/library/advanced-search', {
    method: 'POST', body: JSON.stringify(value),
  }),
  analysis: () => request<AnalysisSummary>('/api/analysis'),
  analyze: (trackIds: string[] = [], missingOnly = true) => request<AnalysisEnqueueResponse>('/api/analysis', {
    method: 'POST', body: JSON.stringify({ trackIds, missingOnly }),
  }),
  search: (q: string, source = 'all') => request<SearchResponse>(`/api/search?q=${encodeURIComponent(q)}&source=${source}`),
  favorites: () => request<Track[]>('/api/favorites'),
  setFavorite: (id: string, favorite: boolean) => request(`/api/favorites/${id}`, { method: favorite ? 'PUT' : 'DELETE' }),
  playlists: () => request<PlaylistSummary[]>('/api/playlists'),
  playlist: (id: string) => request<PlaylistDetail>(`/api/playlists/${id}`),
  createPlaylist: (name: string) => request<PlaylistSummary>('/api/playlists', { method: 'POST', body: JSON.stringify({ name }) }),
  updatePlaylist: (id: string, trackIds: string[], expectedVersion: number) => request<PlaylistDetail>(`/api/playlists/${id}`, {
    method: 'PUT', body: JSON.stringify({ trackIds, expectedVersion }),
  }),
  history: () => request<HistoryEntry[]>('/api/history'),
  recordHistory: (trackId: string) => request('/api/history', { method: 'POST', body: JSON.stringify({ trackId }) }),
  sources: () => request<SourceConfig[]>('/api/settings/sources'),
  createSource: (value: { name: string; apiUrl: string; apiKey: string }) => request<SourceConfig>('/api/settings/sources', {
    method: 'POST', body: JSON.stringify({ ...value, enabled: true }),
  }),
  downloads: () => request<DownloadJob[]>('/api/downloads'),
  retryDownload: (id: string) => request<DownloadJob>(`/api/downloads/${id}/retry`, { method: 'POST' }),
  download: (track: Track) => request<DownloadJob>('/api/downloads', {
    method: 'POST', body: JSON.stringify({ trackId: track.id, title: track.title, artist: track.artist }),
  }),
  rooms: () => request<RoomSummary[]>('/api/rooms'),
  createRoom: (name: string, id?: string) => request<RoomSummary>('/api/rooms', { method: 'POST', body: JSON.stringify({ name, id }) }),
  room: (id: string) => request<RoomDetail>(`/api/rooms/${id}`),
  updateRoom: (id: string, state: Partial<RoomPlaybackState>) => request<RoomDetail>(`/api/rooms/${id}/state`, {
    method: 'PUT', body: JSON.stringify(state),
  }),
  deleteRoom: (id: string) => request<void>(`/api/rooms/${id}`, { method: 'DELETE' }),
}
