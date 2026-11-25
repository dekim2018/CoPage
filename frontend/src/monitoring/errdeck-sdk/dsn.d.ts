/**
 * DSN 은 `https://{공개키}@{호스트}/{프로젝트ID}` 다(5.2). 브라우저는 키를 헤더 대신 쿼리 `?key=` 로 보낸다.
 * 사용자 정의 헤더를 붙이면 브라우저가 사전 요청(OPTIONS)을 먼저 보내 요청 수가 두 배가 되기 때문이다.
 */
export declare function ingestUrl(dsn: string): string;
