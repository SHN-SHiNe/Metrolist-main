type CryptoSource = {
  randomUUID?: () => string
  getRandomValues: (array: Uint8Array) => unknown
}

export function randomId(source?: CryptoSource): string {
  const cryptoSource = source ?? {
    randomUUID: typeof crypto.randomUUID === 'function' ? () => crypto.randomUUID() : undefined,
    getRandomValues: (array: Uint8Array) => crypto.getRandomValues(array as Uint8Array<ArrayBuffer>),
  }
  if (typeof cryptoSource.randomUUID === 'function') return cryptoSource.randomUUID()

  const bytes = new Uint8Array(16)
  cryptoSource.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
