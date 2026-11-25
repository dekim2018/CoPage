import { Tracer } from './tracing';
import type { Transport } from './transport';
import type { Breadcrumb, Options } from './types';
/**
 * 브라우저 SDK 의 중심(11.1). 에러를 잡으면 이벤트로 바꿔 길이가 정해진 큐에 넣고, 2초마다 묶어 보낸다.
 * 큐가 차면 기다리지 않고 버리고 센다. 429 와 503 의 Retry-After 동안은 보내지 않는다. SDK 안의 문제는 밖으로 던지지 않는다.
 */
export declare class Client {
    private readonly url;
    private readonly options;
    private readonly transport;
    private readonly now;
    private queue;
    private crumbs;
    private discarded;
    private pausedUntil;
    private pauseReason;
    private disabled;
    private sending;
    private readonly seen;
    private readonly folder;
    private readonly queueSize;
    private readonly maxBreadcrumbs;
    readonly tracer: Tracer | null;
    constructor(url: string, options: Options, transport: Transport, now?: () => number);
    /** 감시 대상이 준 가리기. 실패하면 원문 대신 가린 표시를 보낸다. */
    readonly redact: (text: string) => string;
    capture(error: unknown, tags?: Record<string, string>): void;
    addBreadcrumb(crumb: Omit<Breadcrumb, 'timestamp'>): void;
    /** 모인 것을 압축해 보낸다. 보내는 중이면 다음 차례로 미룬다. */
    flush(): Promise<void>;
    /** 탭이 숨겨질 때 남은 것을 보낸다. 압축은 비동기라 페이지가 사라지기 전에 끝난다는 보장이 없어 압축하지 않는다. */
    flushBeacon(): void;
    /** 트랜잭션은 따로 보낸다. 대기열이 나뉘어 있어 에러가 트랜잭션에 밀리지 않는다(5.4). 실패하면 버린다. */
    private flushTransactions;
    /** 시험용: 큐에 든 수. */
    pending(): number;
    private take;
    private handle;
    private enqueue;
    private count;
    private restore;
    private event;
}
