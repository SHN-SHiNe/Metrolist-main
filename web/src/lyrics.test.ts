import { describe, expect, it } from 'vitest'
import { activeLyricIndex, lyricMatches, parseLyrics, seekTimeForLyric, selectedLyricText } from './lyrics'

describe('parseLyrics', () => {
  it('parses, expands and sorts LRC timestamps while ignoring metadata', () => {
    const result = parseLyrics('[ar:SHiNe]\n[00:10.50][00:20.5]副歌\n[00:02.125]开场\n[offset:+300]')

    expect(result.timed).toBe(true)
    expect(result.embeddedOffsetMs).toBe(300)
    expect(result.lines.map((line) => [line.timeSeconds, line.text])).toEqual([
      [2.125, '开场'],
      [10.5, '副歌'],
      [20.5, '副歌'],
    ])
  })

  it('keeps ordinary text lyrics editable and readable', () => {
    const result = parseLyrics('第一行\n\n第二行')
    expect(result.timed).toBe(false)
    expect(result.lines.map((line) => line.text)).toEqual(['第一行', '第二行'])
  })
})

describe('synchronized lyric helpers', () => {
  const lines = parseLyrics('[00:01.00]一\n[00:03.00]二\n[00:05.00]三').lines

  it('selects the latest elapsed line after applying the whole-track offset', () => {
    expect(activeLyricIndex(lines, 2.9)).toBe(0)
    expect(activeLyricIndex(lines, 2.9, -200)).toBe(1)
    expect(activeLyricIndex(lines, 0.5)).toBe(-1)
  })

  it('seeks with the same offset and clamps at zero', () => {
    expect(seekTimeForLyric(lines[1], 500)).toBe(3.5)
    expect(seekTimeForLyric(lines[0], -2000)).toBe(0)
  })

  it('matches searches case-insensitively', () => {
    expect(lyricMatches({ id: 'x', text: 'Shine On', timeSeconds: 1 }, 'shine')).toBe(true)
    expect(lyricMatches({ id: 'x', text: 'Shine On', timeSeconds: 1 }, 'moon')).toBe(false)
  })

  it('copies selected lines in lyric order rather than click order', () => {
    expect(selectedLyricText(lines, [2, 0])).toBe('一\n三')
    expect(selectedLyricText(lines, [])).toBe('')
  })
})
