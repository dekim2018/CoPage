import * as Sentry from '@sentry/vue'
import type { App } from 'vue'
import { scrubDeep } from './scrub'

/**
 * 화면의 에러 모니터링 (D-49). 처리하지 못한 예외와 Vue 오류를 보낸다. 사용자 정보는 싣지 않고, 보내기 전에 주소의 티켓과 토큰을
 * 가린다. 성능 추적과 화면 녹화는 쓰지 않는다.
 */
export function startMonitoring(app: App, dsn: string): void {
  Sentry.init({
    app,
    dsn,
    environment: import.meta.env.MODE,
    // 사용자 정보, 쿠키, 헤더, 요청과 응답 본문, 주소의 쿼리는 모으지 않는다. 기본값은 모두 모은다.
    dataCollection: { userInfo: false, cookies: false, httpHeaders: false, httpBodies: [], urlQueryParams: false },
    beforeSend: (event) => scrubDeep(event),
    beforeBreadcrumb: (breadcrumb) => scrubDeep(breadcrumb),
  })
}
