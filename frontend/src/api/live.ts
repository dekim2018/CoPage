import { currentAccessToken, refresh } from './http'

/**
 * 알림 연결(SSE, D-46). EventSource 는 헤더를 실을 수 없어 fetch 로 열고 스트림을 직접 읽는다. 끊기면 2초부터 30초까지 늘려 가며
 * 다시 붙는다. 401 이면 토큰을 재발급해 본다.
 */
export function connectLive(onEvent: (type: string, data: unknown) => void): () => void {
  let stopped = false
  let controller: AbortController | null = null
  let delay = 2_000

  const run = async () => {
    while (!stopped) {
      controller = new AbortController()
      try {
        const token = currentAccessToken()
        const response = await fetch('/api/notifications/stream', {
          headers: token === null ? {} : { Authorization: `Bearer ${token}` },
          credentials: 'same-origin',
          signal: controller.signal,
        })
        if (response.status === 401) {
          await refresh().catch(() => undefined)
        } else if (response.ok && response.body !== null) {
          delay = 2_000
          await read(response.body, onEvent)
        }
      } catch {
        // 끊겼다. 잠시 뒤 다시 붙는다.
      }
      if (!stopped) {
        await new Promise((resolve) => setTimeout(resolve, delay))
        delay = Math.min(30_000, delay * 2)
      }
    }
  }
  void run()
  return () => {
    stopped = true
    controller?.abort()
  }
}

async function read(body: ReadableStream<Uint8Array>, onEvent: (type: string, data: unknown) => void): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) {
      return
    }
    buffer += decoder.decode(value, { stream: true })
    let end = buffer.indexOf('\n\n')
    while (end >= 0) {
      const chunk = buffer.slice(0, end)
      buffer = buffer.slice(end + 2)
      let type = 'message'
      const data: string[] = []
      for (const line of chunk.split('\n')) {
        if (line.startsWith('event:')) {
          type = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          data.push(line.slice(5).trimStart())
        }
      }
      if (data.length > 0) {
        try {
          onEvent(type, JSON.parse(data.join('\n')))
        } catch {
          onEvent(type, null)
        }
      }
      end = buffer.indexOf('\n\n')
    }
  }
}
