import { describe, expect, it } from 'vitest'
import { mainNavigation, sectionFromHash } from './navigation'

describe('SHiNe navigation', () => {
  it('preserves the Android information architecture and only adds sync rooms', () => {
    expect(mainNavigation.map((item) => item.id)).toEqual(['home', 'search', 'library', 'local', 'rooms'])
  })

  it('falls back to home for stale or unknown hashes', () => {
    expect(sectionFromHash('#favorites')).toBe('library')
    expect(sectionFromHash('#playlists')).toBe('library')
    expect(sectionFromHash('#wat')).toBe('home')
  })
})
