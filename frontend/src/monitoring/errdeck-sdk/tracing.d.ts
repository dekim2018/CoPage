/** core 의 트랜잭션 JSON(8.2)과 같은 이름을 쓴다. */
export interface ErrDeckSpan {
    span_id: string;
    parent_span_id?: string;
    op: string;
    description: string;
    start_offset_ms: number;
    duration_ms: number;
    repeat?: number;
}
export interface ErrDeckTransaction {
    trace_id: string;
    span_id: string;
    name: string;
    op: string;
    start_timestamp: string;
    duration_ms: number;
    status: string;
    environment?: string;
    release?: string;
    partial?: boolean;
    spans: ErrDeckSpan[];
}
export interface TracingOptions {
    /** 시작할 때 뽑는 비율. 기본 0.1(8.4) */
    tracesSampleRate?: number;
    /** 뽑히지 않았어도 이보다 느리면 남긴다. 기본 1000ms */
    slowThresholdMs?: number;
    /**
     * traceparent 를 붙일 주소. 기본은 같은 출처만이다. 다른 출처에 붙이면 브라우저가 사전 요청을 보내고, 그쪽 서버가
     * 헤더를 허용해야 한다.
     */
    tracePropagationTargets?: (string | RegExp)[];
}
/**
 * 페이지 이동 하나를 트랜잭션으로, 그 안의 API 호출을 스팬으로 남긴다(8.3). 호출이 모두 끝나고 잠시 조용하면 끝낸다.
 * 서버로 가는 호출에 traceparent 를 붙여 서버의 트랜잭션과 같은 trace_id 로 잇는다.
 */
export declare class Tracer {
    private readonly options;
    private readonly now;
    private active;
    private done;
    constructor(options: TracingOptions & {
        environment?: string;
        release?: string;
        redact?: (text: string) => string;
    }, now?: () => number);
    traceId(): string | undefined;
    start(name: string, op: string): void;
    rename(name: string): void;
    markError(): void;
    /** API 호출 하나를 연다. 붙일 traceparent 와 닫는 함수를 돌려준다. */
    startSpan(op: string, description: string, url: string): {
        header?: string;
        finish: (status?: number) => void;
    };
    /** 끝난 것을 꺼낸다. 전송은 Client 가 한다. */
    take(): ErrDeckTransaction[];
    /** 진행 중인 것을 끝낸다. 탭을 떠날 때 부른다. */
    end(): void;
    /** 같은 호출이 연속으로 반복되면 하나로 접고 횟수를 센다(8.5). */
    private add;
    private schedule;
    private clean;
    private propagates;
}
