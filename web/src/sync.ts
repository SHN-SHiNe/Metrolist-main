export type ClockSample = { sentAt: number; receivedAt: number; serverTime: number }

export function estimateServerOffset(samples: ClockSample[]): number {
  if (samples.length === 0) return 0
  const best = [...samples].sort((a, b) => (a.receivedAt - a.sentAt) - (b.receivedAt - b.sentAt)).slice(0, 5)
  const offsets = best.map((sample) => sample.serverTime - (sample.sentAt + sample.receivedAt) / 2).sort((a, b) => a - b)
  return offsets[Math.floor(offsets.length / 2)]
}

export function scheduledDelay(effectiveAt: number, serverOffset: number, deviceDelay: number, now: number): number {
  return Math.max(0, effectiveAt - (now + serverOffset) + deviceDelay)
}

export function driftCorrection(driftMs: number): { rate: number; seek: boolean } {
  if (Math.abs(driftMs) > 250) return { rate: 1, seek: true }
  if (driftMs > 40) return { rate: 1.01, seek: false }
  if (driftMs < -40) return { rate: 0.99, seek: false }
  return { rate: 1, seek: false }
}
