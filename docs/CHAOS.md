# 장애 실험 결과

docs/11-TEST-PLAN.md 13.2 의 장애 실험을 실제로 돌린 결과다. 실험은 자동 시험으로 만들어 CI 에서 계속 돈다. 표의 시간은 로컬 개발 기기(Docker Desktop)에서 잰 한 번의 값이다. 운영 환경의 값이 아니다.

위 표는 노드 한 대 구성이고, 아래 "노드 두세 대" 절은 소유 리스와 프록시를 갖춘 다중 노드 구성(D-43)이다.

| 실험 | 방법 | 결과 | 시험 |
| --- | --- | --- | --- |
| Redis 중단 | Redis 컨테이너 일시 정지 (연결은 받되 답하지 않음) | 토큰 확인, 티켓, 로그인, 재발급, 로그아웃, 핸드셰이크가 503. 열린 편집 연결은 계속 확정된다. 생존 검사(liveness)는 200, 묶은 health 는 503. 되살리면 같은 Refresh 쿠키로 재발급된다 | `RedisOutageTest` |
| PostgreSQL 중단 | PostgreSQL 컨테이너 일시 정지 | 편집 중인 문서의 커밋 전 중복 확인이 소켓 시간 한도(시험 2초, 운영 기본 10초) 뒤 실패하고 세션을 버린다(4009). 정지 후 2,060ms 에 닫혔다. 그동안 API 는 503. 되살린 뒤 재접속한 클라이언트가 보내지 못한 편집을 같은 순번으로 다시 보내 45ms 만에 한 번만 확정되었다 | `PostgresOutageTest` |
| 클라이언트 단절 | 무작위 편집 중 클라이언트가 무작위로 끊고 다시 붙는다 | 같은 묶음이 두 번 적용되지 않고 빠지는 묶음도 없다. 모든 클라이언트가 서버와 같은 문서가 된다 | `CollabWebSocketIntegrationTest` |
| 느린 클라이언트 | 실제 서버에서 한 연결이 읽기를 멈추고(TCP 버퍼가 찬다), 다른 사람이 60KB 편집을 넣고 지우기를 80번 되풀이한다(약 2.4MB) | 다른 사람의 ack 최대 27ms, 남의 편집 반영 최대 37ms. 멈춘 연결만 서버가 버렸다(대기열 한도). 받는 쪽이 막혀 서버의 닫기 프레임(4011)이 닿지 못하고 클라이언트에는 1006 으로 보였다. 둘 다 재접속하는 종료다. 버릴 때 WARN 로그를 남긴다 | `SlowClientTest`, `WebSocketConnectionTest`(단위) |
| 노드 강제 종료 | 부하(loadgen `reconnect=true`) 중 서버 프로세스를 `kill -9` 하고 다시 띄운다 | 아래 표. ack 받은 묶음을 잃은 경우 0(잃었다면 이어 붙는 hello 의 lastRev 가 서버보다 커서 4008 이거나 전체 문서를 받는다. 둘 다 0번). 모든 문서가 수렴. 끊김부터 첫 ack 까지 p99 4.4~6.3초(서버 시작 3.3~4.0초 포함) | `loadgen`(수동 실험, 아래 명령) |
| 재접속 몰림 | 위 실험을 연결 1,000개로 | 1,000개가 모두 이어 붙고, 서버 시작 뒤 약 2초 안에 편집을 재개했다. 서버 오류 0 | `loadgen` |
| DB 지연 | Toxiproxy 로 PostgreSQL 의 모든 응답에 200ms 를 더하고 네 명이 15번씩 친다 | 60개가 모두 확정되고 수렴했다. 남에게 보인 묶음은 모두 이미 저장되어 있었다(Toxiproxy 를 거치지 않는 연결로 확인). 커밋 묶음 크기 최대 4. 처음에는 30.6초(초당 2.0개)가 걸렸다. 커밋 하나가 묶음 수에 비례해 DB 를 왕복했다. 고친 뒤(PERF-2, D-38) 7.0초(초당 8.6개) | `DbLatencyTest` |

### 노드 강제 종료와 재접속 몰림

노트북 한 대에서 서버(힙 1GB)와 loadgen 을 함께 돌렸다. 서버를 죽이고 바로(첫 실험은 2초 뒤) 다시 띄웠다.

| 구성 | 끊긴 연결 | 이어 붙음 | 대기 편집을 버린 재접속 | 끊김부터 첫 ack 까지 p50 / p99 | 서버 시작 | 수렴 |
| --- | --- | --- | --- | --- | --- | --- |
| 문서 1, 편집자 20, 초당 5타 (2초 뒤 재시작) | 20 (1006) | 20 | 0 | 5.7 / 5.8초 | 3.3초 | 1/1 |
| 문서 100 x 3명, 초당 2타 | 300 | 300 | 0 | 4.3 / 4.4초 | 3.3초 | 100/100 |
| 문서 250 x 4명, 초당 1타 (재접속 몰림) | 1,000 | 1,000 | 0 | 5.7 / 6.3초 | 4.0초 | 250/250 |

- 끊긴 동안 친 글자는 클라이언트 buffer 에 쌓였다가 재접속 뒤 resync 로 한 번만 확정되었다(nack 0).
- 목표(1.4, 노드 장애 후 편집 재개 10초 이내)는 노드 한 대를 다시 띄우는 경우 맞는다. 다중 노드에서 다른 노드가 문서를 넘겨받는 경우는 아래 "노드 두세 대" 절에 있다(p99 4.6초).
- 재접속 몰림에서 loadgen 의 재시도 간격은 0.25~0.75초 무작위다(브라우저는 1초부터 두 배씩, 0.5~1배 무작위). 몰림 동안 서버 ack 처리의 최대가 1.3초까지 올랐다가 돌아왔다.

다시 돌리는 법:

```
docker compose up -d && ./gradlew :app:bootJar
java -Xmx1g -jar app/build/libs/app-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev &
./gradlew :loadgen:run --args="scenario=many-docs docs=250 editors=4 seconds=90 rate=1 reconnect=true" &
# 접속이 끝나고 몇 초 뒤
kill -9 <서버 pid> && java -Xmx1g -jar app/build/libs/app-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev &
```

## 실험으로 찾아 고친 것

- PostgreSQL 이 답하지 않으면 JDBC 질의가 끝없이 기다렸다(일시 정지한 DB 로 40초 넘게 확인). 문서 세션의 실행 흐름이 영영 막힌다. `socketTimeout`(기본 10초, `COPAGE_DB_SOCKET_TIMEOUT_SECONDS`)을 두었다
- 묶음을 처리하다 저장소 읽기(커밋 전 중복 확인)가 실패하면 예외가 실행기로 새어, 묶음이 말없이 사라지고 클라이언트는 답을 영영 받지 못했다. 커밋 실패와 같이 세션을 버려(4009) 재접속하게 했다
- Redis 가 돌아오면 시간 초과로 포기한 재발급 명령이 늦게 실행되어 세션을 잃었다(D-08)
- 받 쪽이 느린 연결을 버릴 때 로그가 없었다(DEBUG). WARN 으로 남긴다.
- DB 지연 실험에서 커밋 하나가 묶음 N 개에 DB 왕복 2N+1 번을 했다(묶음마다 중복 확인 SELECT 하나, INSERT 하나, 그리고 COMMIT). 문장 로그(log_statement=all)로 확인했다. 중복 확인을 연결의 첫 묶음에서만 하고 INSERT 를 문장 하나로 합쳐 묶음 크기와 상관없이 왕복 한 번으로 줄였다(PERF-2, D-38).

## 노드 두세 대 (D-43)

`deploy/cluster/compose.yaml` 로 노드 2대(실험 3에서 3대), nginx 프록시(문서 ID 일관 해시), PostgreSQL, Redis 를 띄우고 loadgen 을 프록시(8088)로 붙였다(`reconnect=true`, 초당 2타). 같은 노트북의 Docker Desktop 이다. 끝날 때마다 연산 로그에 빈틈이나 겹친 리비전이 있는 문서를 셌다(`count(*) <> max(rev)` 인 문서 수, 모두 0).

| 실험 | 방법 | 결과 |
| --- | --- | --- |
| 소유 노드 강제 종료 | 문서 20개 x 3명 중 node1(9문서)을 `docker kill` | node1 의 연결 27개가 끊기고(1006) node2 가 리스 만료(6초)를 기다렸다 맡았다. 그동안의 재접속은 4009(55번). 끊김부터 첫 ack 까지 p50 4.4초, p99 4.6초. 대기 편집을 버린 재접속 0, nack 0, 20/20 수렴, 로그 이상 0 |
| 소유 노드 일시 정지 | 문서 20개를 맡은 node2 를 `docker pause` 10초(리스 6초보다 길게) | 깨어난 node2 가 이어 쥐기에서 리스를 잃은 것을 알고 세션을 버렸다(4009, 연결 66개 재접속). 그동안의 편집은 클라이언트에 쌓였다가 다시 보내졌다(ack 최대 10.1초). 20/20 수렴, 로그 이상 0. 프록시는 멈춘 노드를 살아 있다고 보아 그동안 다른 노드가 맡지 않았다. 다른 노드가 맡은 뒤 옛 노드가 깨어나 쓰는 경우는 `MultiNodeTest`(리스를 지워 흉내) 가 기본 키로 막히는 것을 확인한다 |
| 노드 추가 | 문서 30개 x 2명, 실행 중 node3 를 띄우고 중간에 문서마다 한 명씩 더 들어온다(loadgen `lateJoin=true`) | 해시가 node3 로 바뀐 문서 11개를 node3 이 넘겨받았다. 넘기며 닫힌 연결 33개(4009)가 p99 1.1초에 이어 편집했다. 30/30 수렴, 로그 이상 0 |
| 노드 계획 종료 | 문서 20개 x 3명 중 node3 을 `docker stop`(SIGTERM) | 내리기 전에 세션을 넘기고 리스를 바로 풀어, 연결 18개가 p99 0.95초에 다른 노드에서 이어 편집했다(강제 종료의 4.6초와 비교). 로그 이상 0 |

자동 시험(`MultiNodeTest`, 한 JVM 에 서버 둘): 한 문서는 한 노드만 맡고 다른 노드로 온 연결은 넘겨받은 뒤 이어진다, 리스를 잃은 노드는 세션을 버리고 같은 리비전이 두 번 들어가지 않는다, 계획 종료는 리스를 바로 푼다(다른 노드에서 111ms 에 이어 붙음), 다른 노드에서 바꾼 권한도 1초 안에 적용된다(43ms).

다시 돌리는 법:

```
./gradlew :app:bootJar
docker compose -f deploy/cluster/compose.yaml up -d --build postgres redis node1 node2 proxy
./gradlew :loadgen:run --args="scenario=many-docs docs=20 editors=3 seconds=60 rate=2 reconnect=true base=http://localhost:8088"
# 실행 중에: docker kill cluster-node1-1 / docker pause cluster-node2-1 (10초 뒤 unpause) / docker stop ...
# 노드 추가: lateJoin=true 를 주고 실행 중에 docker compose -f deploy/cluster/compose.yaml up -d node3
docker exec cluster-postgres-1 psql -U copage -d copage -c \
  "SELECT count(*) FROM (SELECT doc_id FROM doc_ops GROUP BY doc_id HAVING count(*) <> max(rev)) x"
```

## 남은 실험

| 실험 | 계획 |
| --- | --- |
| 실제 서버 여러 대(다른 기기)에서의 같은 실험 | 배포 준비(7단계)와 함께. 지금은 한 기기의 컨테이너다 |
| 프록시가 멈춘 노드를 빼는 경우 | 운영의 상태 확인(준비 상태)과 함께. nginx 오픈 소스는 능동 상태 확인이 없다 |
