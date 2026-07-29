import type { DownloadJob, HistoryEntry, PlaylistDetail, PlaylistSummary, RoomSummary, SearchResponse, SourceConfig, Track, TrackPage } from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { error?: string } | null
    throw new Error(payload?.error ?? `${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const api = {
  library: (q = '') => request<TrackPage>(`/api/library?limit=200&q=${encodeURIComponent(q)}`),
  scan: () => request('/api/scans', { method: 'POST' }),
  deleteTrack: (id: string) => request(`/api/library/${id}`, { method: 'DELETE' }),
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
  createRoom: (name: string) => request<RoomSummary>('/api/rooms', { method: 'POST', body: JSON.stringify({ name }) }),
}
