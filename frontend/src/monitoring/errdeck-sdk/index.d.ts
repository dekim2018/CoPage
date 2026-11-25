import type { App } from 'vue';
import type { Options } from './types';
export type { Options } from './types';
export { Client } from './client';
export type { TracingOptions } from './tracing';
/** 코드에서 직접 보낸다. SDK 가 꺼져 있으면 아무것도 하지 않는다. */
export declare function captureException(error: unknown, tags?: Record<string, string>): void;
/**
 * Vue 플러그인(11.3).
 *
 * ```ts
 * app.use(createErrDeck({ dsn: import.meta.env.VITE_ERRDECK_DSN, release: __RELEASE__, environment: import.meta.env.MODE, router }))
 * ```
 */
export declare function createErrDeck(options: Options): {
    install(app: App): void;
};
