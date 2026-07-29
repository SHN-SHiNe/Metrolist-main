import { useRef } from 'react'
import { swipeAction } from '../swipeGesture'
import type { PlayerController } from '../usePlayer'
import { AlbumArt, formatTime } from '../components/TrackList'
import { Icon } from '../components/Icon'

type RoomIndicator = { roomId: string | null; status: string; members: number }

export function PlayerBar({ player, room, favorite, onFavorite, onToggle, onPrevious, onNext, onSeek, onOpen }: { player: PlayerController; room: RoomIndicator; favorite: boolean; onFavorite: () => void; onToggle: () => void; onPrevious: () => void; onNext: () => void; onSeek: (seconds: number) => void; onOpen: () => void }) {
  const swipeStart = useRef<number | null>(null)
  const swiped = useRef(false)
  const finishSwipe = (x: number) => {
    if (swipeStart.current === null) return
    const action = swipeAction(swipeStart.current, x)
    swipeStart.current = null
    swiped.current = Boolean(action)
    if (action === 'next') onNext()
    if (action === 'previous') onPrevious()
  }
  return <footer className={`player-bar ${player.current ? 'has-track' : ''}`}>
    <button className="player-track" onPointerDown={(event) => { swipeStart.current = event.clientX; swiped.current = false }} onPointerUp={(event) => finishSwipe(event.clientX)} onPointerCancel={() => { swipeStart.current = null }} onClick={() => { if (swiped.current) { swiped.current = false; return }; onOpen() }} disabled={!player.current} title="点击展开，左右滑动切歌">
      <AlbumArt title={player.current?.title ?? 'SHiNe'} artworkUrl={player.current?.artworkUrl} small />
      <span><strong>{player.current?.title ?? '选择一首歌开始播放'}</strong><small>{player.current?.artist ?? 'SHiNe MUSIC'}</small></span>
    </button>
    <div className="mobile-player-actions"><button className={`icon-button ${favorite ? 'favorite' : ''}`} onClick={onFavorite} disabled={!player.current} aria-label={favorite ? '取消收藏' : '收藏'}><Icon name="heart" /></button></div>
    <div className="player-center"><div className="transport"><button className="icon-button" onClick={onPrevious} aria-label="上一首"><Icon name="previous" /></button><button className="player-toggle" onClick={onToggle} disabled={!player.current} aria-label={player.playing ? '暂停' : '播放'}><Icon name={player.playing ? 'pause' : 'play'} /></button><button className="icon-button" onClick={onNext} aria-label="下一首"><Icon name="next" /></button></div><div className="progress-row"><span>{formatTime(player.position)}</span><input aria-label="播放进度" type="range" min="0" max={player.duration || 1} step="0.1" value={Math.min(player.position, player.duration || 1)} onChange={(event) => onSeek(Number(event.target.value))} /><span>{formatTime(player.duration)}</span></div></div>
    <div className="player-extras"><span className={`room-pill ${room.status}`}>{roomPlayerLabel(room)}</span><Icon name="volume" /><input aria-label="音量" type="range" min="0" max="1" step="0.01" value={player.volume} onChange={(event) => player.setVolume(Number(event.target.value))} /></div>
  </footer>
}

export function roomPlayerLabel(room: RoomIndicator) {
  if (!room.roomId) return '本机'
  if (room.status === 'joined') return `${room.members} 台同步`
  if (room.status === 'connecting') return '连接中'
  return room.status === 'reconnecting' ? '重连中' : '同步异常'
}
