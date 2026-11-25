import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import './style.css'

const app = createApp(App).use(createPinia()).use(router)

// 에러 모니터링 (D-49). 빌드할 때 DSN 을 주었을 때만 SDK 를 내려받는다. 없으면 아무것도 보내지 않는다.
const dsn = import.meta.env.VITE_SENTRY_DSN
if (dsn) {
  void import('./monitoring/sentry').then(({ startMonitoring }) => startMonitoring(app, dsn))
}

// ErrDeck (D-53). 같은 방식으로 DSN 을 주었을 때만 SDK 를 내려받는다. 라우터를 넘기므로 마운트 전에 붙인다.
const errdeckDsn = import.meta.env.VITE_ERRDECK_DSN
if (errdeckDsn) {
  void import('./monitoring/errdeck').then(({ startErrDeck }) => {
    startErrDeck(app, router, errdeckDsn)
    app.mount('#app')
  })
} else {
  app.mount('#app')
}
