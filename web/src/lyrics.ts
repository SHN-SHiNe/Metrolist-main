export type LyricLine = {
  id: string
  text: string
  timeSeconds: number | null
}

export type ParsedLyrics = {
  lines: LyricLine[]
  timed: boolean
  embeddedOffsetMs: number
}

const TIMESTAMP = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g
const METADATA = /^\[(?:ar|al|ti|by|re|ve|length):/i
const OFFSET = /^\[offset:([+-]?\d+)\]/i

export function parseLyrics(value: string): ParsedLyrics {
  const timed: Array<LyricLine & { order: number }> = []
  const plain: LyricLine[] = []
  let embeddedOffsetMs = 0
  let order = 0

  for (const rawLine of value.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line) continue
    const offset = line.match(OFFSET)
    if (offset) {
      embeddedOffsetMs = Number(offset[1]) || 0
      continue
    }
    if (METADATA.test(line)) continue

    const stamps = [...line.matchAll(TIMESTAMP)]
    const text = line.replace(TIMESTAMP, '').trim()
    if (stamps.length) {
      if (!text) continue
      for (const stamp of stamps) {
        const minutes = Number(stamp[1])
        const seconds = Number(stamp[2])
        const fraction = stamp[3] ? Number(stamp[3]) / 10 ** stamp[3].length : 0
        timed.push({
          id: `${minutes}:${seconds}:${stamp[3] ?? ''}:${order}`,
          text,
          timeSeconds: minutes * 60 + seconds + fraction,
          order: order++,
        })
      }
    } else {
      plain.push({ id: `plain:${plain.length}`, text: line, timeSeconds: null })
    }
  }

  if (!timed.length) return { lines: plain, timed: false, embeddedOffsetMs }
  timed.sort((left, right) => (left.timeSeconds ?? 0) - (right.timeSeconds ?? 0) || left.order - right.order)
  return {
    lines: timed.map(({ order: _order, ...line }) => line),
    timed: true,
    embeddedOffsetMs,
  }
}

export function activeLyricIndex(lines: LyricLine[], positionSeconds: number, offsetMs = 0) {
  let active = -1
  for (let index = 0; index < lines.length; index++) {
    const time = lines[index].timeSeconds
    if (time == null) continue
    if (time + offsetMs / 1000 <= positionSeconds + 0.02) active = index
    else break
  }
  return active
}

export function seekTimeForLyric(line: LyricLine, offsetMs = 0) {
  return line.timeSeconds == null ? null : Math.max(0, line.timeSeconds + offsetMs / 1000)
}

export function lyricMatches(line: LyricLine, query: string) {
  const normalized = query.trim().toLocaleLowerCase()
  return !normalized || line.text.toLocaleLowerCase().includes(normalized)
}

export function selectedLyricText(lines: LyricLine[], selectedIndexes: Iterable<number>) {
  const selected = new Set(selectedIndexes)
  return lines.filter((_, index) => selected.has(index)).map((line) => line.text).join('\n')
}
