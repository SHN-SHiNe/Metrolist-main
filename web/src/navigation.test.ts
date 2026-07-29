import { describe, expect, it } from 'vitest'
import { mainNavigation, sectionFromHash } from './navigation'

describe('SHiNe navigation', () => {
  it('preserves the original four-item mobile information architecture', () => {
    expect(mainNavigation.map((item) => item.id)).toEqual(['home', 'search', 'library', 'local'])
  })

  it('falls back to home for stale or unknown hashes', () => {
    expect(sectionFromHash('#favorites')).toBe('library')
    expect(sectionFromHash('#playlists')).toBe('library')
    expect(sectionFromHash('#wat')).toBe('home')
  })
})
