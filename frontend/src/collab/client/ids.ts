/**
 * UUIDv7 (RFC 9562). 블록 ID 로 쓴다 (docs/04-OT-DESIGN.md 6.1). 앞 48 비트가 밀리초 시각이라 만든 순서대로 정렬되고, 나머지는
 * 무작위라 서버 왕복 없이 만들어도 겹치지 않는다.
 */
export function uuidv7(now: number = Date.now()): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  let time = now
  for (let i = 5; i >= 0; i--) {
    bytes[i] = time % 256
    time = Math.floor(time / 256)
  }
  bytes[6] = ((bytes[6] as number) & 0x0f) | 0x70
  bytes[8] = ((bytes[8] as number) & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
