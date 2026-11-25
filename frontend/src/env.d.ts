/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 에러 모니터링(Sentry) DSN. 빌드할 때 준다. 없으면 모니터링을 켜지 않는다 (D-49) */
  readonly VITE_SENTRY_DSN?: string
  // ErrDeck (D-53)
  readonly VITE_ERRDECK_DSN?: string
  readonly VITE_ERRDECK_TRACE_SAMPLE_RATE?: string
  readonly VITE_COPAGE_RELEASE?: string
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, unknown>
  export default component
}
