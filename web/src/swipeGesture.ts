export type SwipeAction = 'previous' | 'next'

export function swipeAction(startX: number, endX: number, threshold = 56): SwipeAction | null {
  const distance = endX - startX
  if (Math.abs(distance) < threshold) return null
  return distance > 0 ? 'previous' : 'next'
}
