import { describe, expect, it } from 'vitest'
import { appendRoomTrack, insertRoomTrackNext, moveRoomTrack, removeRoomTrack } from './roomQueue'

describe('authoritative room queue edits', () => {
  it('preserves more than one hydration batch of unknown IDs while inserting and appending', () => {
    const queue = Array.from({ length: 150 }, (_, index) => `unknown-${index}`)

    const inserted = insertRoomTrackNext(queue, 'unknown-74', 'known-next')
    expect(inserted).toHaveLength(151)
    expect(inserted.slice(73, 78)).toEqual(['unknown-73', 'unknown-74', 'known-next', 'unknown-75', 'unknown-76'])
    expect(inserted.at(-1)).toBe('unknown-149')

    const appended = appendRoomTrack(inserted, 'known-last')
    expect(appended).toHaveLength(152)
    expect(appended.at(-1)).toBe('known-last')
    expect(appendRoomTrack(appended, 'unknown-149')).toBe(appended)
  })

  it('moves and removes by track ID without rebuilding from hydrated tracks', () => {
    const queue = Array.from({ length: 150 }, (_, index) => `unknown-${index}`)

    const moved = moveRoomTrack(queue, 'unknown-120', -1)
    expect(moved.slice(118, 122)).toEqual(['unknown-118', 'unknown-120', 'unknown-119', 'unknown-121'])
    expect(moved).toHaveLength(queue.length)

    const removed = removeRoomTrack(moved, 'unknown-75', 'unknown-75')
    expect(removed.queue).toHaveLength(149)
    expect(removed.queue).not.toContain('unknown-75')
    expect(removed.currentTrackId).toBe('unknown-76')
    expect(removed.queue.at(-1)).toBe('unknown-149')
  })

  it('keeps the current track in place when asked to insert it next', () => {
    const queue = ['before', 'current', 'after']
    expect(insertRoomTrackNext(queue, 'current', 'current')).toBe(queue)
  })
})
