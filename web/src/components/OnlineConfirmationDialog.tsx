import { useEffect, useId, useRef } from 'react'
import { AlbumArt } from './TrackList'

export type OnlineConfirmationDialogProps = {
  kind: 'playlist' | 'track'
  title: string
  subtitle: string
  sourceLabel: string
  artworkUrl?: string | null
  onConfirm: () => void
  onCancel: () => void
}

export function OnlineConfirmationDialog({ kind, title, subtitle, sourceLabel, artworkUrl, onConfirm, onCancel }: OnlineConfirmationDialogProps) {
  const titleId = useId()
  const bodyId = useId()
  const dialog = useRef<HTMLDivElement>(null)
  const cancelButton = useRef<HTMLButtonElement>(null)
  const opener = useRef<HTMLElement | null>(null)
  const onCancelRef = useRef(onCancel)
  onCancelRef.current = onCancel

  useEffect(() => {
    if (!opener.current) opener.current = document.activeElement as HTMLElement | null
    const frame = window.requestAnimationFrame(() => cancelButton.current?.focus())
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onCancelRef.current()
        return
      }
      if (event.key !== 'Tab') return
      const controls = [...(dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled)') ?? [])]
      if (!controls.length) return
      const first = controls[0]
      const last = controls.at(-1)!
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      window.cancelAnimationFrame(frame)
      document.removeEventListener('keydown', onKeyDown)
      window.requestAnimationFrame(() => opener.current?.focus({ preventScroll: true }))
    }
  }, [])

  const cancel = () => onCancelRef.current()

  return <div className="online-confirm-layer" onPointerDown={(event) => { if (event.target === event.currentTarget) cancel() }}>
    <div ref={dialog} className="online-confirm-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={bodyId}>
      <AlbumArt title={title} artworkUrl={artworkUrl} hero />
      <div className="online-confirm-copy">
        <span>{sourceLabel}{kind === 'playlist' ? '在线歌单' : '在线歌曲'}</span>
        <h2 id={titleId}>{kind === 'playlist' ? `是否打开这个${sourceLabel}歌单？` : `是否播放这首${sourceLabel}歌曲？`}</h2>
        <p id={bodyId}><strong>{title}</strong>{subtitle && <small>{subtitle}</small>}</p>
      </div>
      <div className="online-confirm-actions">
        <button ref={cancelButton} className="secondary-button" onClick={cancel}>取消</button>
        <button className="primary-button" onClick={onConfirm}>{kind === 'playlist' ? '打开' : '播放'}</button>
      </div>
    </div>
  </div>
}
