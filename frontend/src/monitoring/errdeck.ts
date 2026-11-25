import type { App } from 'vue'
import type { Router } from 'vue-router'
import { createErrDeck } from './errdeck-sdk/errdeck-vue.js'
import { scrub } from './scrub'

/**
 * ErrDeck 연결 (D-53). 처리하지 못한 예외와 Vue 오류, 페이지 이동과 API 호출의 시간(성능 추적)을 보낸다. API 호출에는 traceparent 를
 * 붙여 서버의 요청과 같은 추적으로 잇는다(같은 출처만). 보내기 전에 Sentry 와 같은 규칙(scrub)으로 티켓과 토큰을 가린다.
 */
export function startErrDeck(app: App, router: Router, dsn: string): void {
  app.use(
    createErrDeck({
      dsn,
      environment: import.meta.env.MODE,
      release: import.meta.env.VITE_COPAGE_RELEASE,
      router,
      tracesSampleRate: Number(import.meta.env.VITE_ERRDECK_TRACE_SAMPLE_RATE ?? 0.1),
      redact: (text: string) => scrub(text),
    }),
  )
}
