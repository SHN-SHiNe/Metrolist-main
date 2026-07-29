export function clampSendspinDelay(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(5000, Math.max(0, Math.round(value)))
}

export function sendspinBaseUrl(roomId: string, origin: string): string {
  return new URL(`/api/rooms/${encodeURIComponent(roomId)}`, origin).toString().replace(/\/$/, '')
}
