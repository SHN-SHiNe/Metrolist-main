export function pageFromScroll(scrollLeft: number, pageWidth: number, pageCount: number) {
  if (pageCount <= 1 || pageWidth <= 0) return 0
  return Math.min(pageCount - 1, Math.max(0, Math.round(scrollLeft / pageWidth)))
}

export function pageOffset(page: number, pageWidth: number, pageCount: number) {
  if (pageCount <= 1 || pageWidth <= 0) return 0
  return Math.min(pageCount - 1, Math.max(0, page)) * pageWidth
}
