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

app.mount('#app')
