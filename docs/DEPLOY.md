# 배포

서버 한 대에 Caddy(TLS, 화면 파일), 서버, PostgreSQL, Redis 를 함께 띄우는 구성이다(`deploy/prod/`, D-48). 로드맵 7단계의 "AWS 배포" 는 이 구성을 EC2 한 대에 올리는 것으로 정했다. 실제로 서버를 만들고 도메인을 사는 일은 계정과 비용이 들어 사용자 몫으로 남겼다. 아래 "직접 할 일" 을 따른다.

이 구성은 로컬에서 도메인을 `localhost` 로 두고 끝까지 띄워 확인했다(2026-11-25): 화면 주소가 모두 index.html 로 열리고, API 401, 가입과 로그인, 알림 연결(SSE)이 Caddy 를 지나 바로 `ready` 를 받고, 가입 11번째가 429 와 Retry-After 를 받고, 빈도 제한 키가 Caddy 의 주소가 아닌 클라이언트 주소로 잡히고, 접근 로그에서 티켓과 공유 토큰이 `REDACTED` 로 가려졌다. 실제 인증서 발급(Let's Encrypt)은 공개 도메인이 없어 확인하지 못했다.

## 구성

| 서비스 | 이미지 | 밖으로 여는 포트 | 하는 일 |
| --- | --- | --- | --- |
| web | `deploy/prod/web.Dockerfile` (Caddy 2.8 + 빌드한 화면) | 80, 443 (TCP, UDP) | TLS 인증서 자동 발급과 갱신, `/api/*` 와 `/ws/*` 를 서버로, 나머지는 화면 파일. 보안 헤더, 비밀을 가린 접근 로그 |
| app | 저장소 루트의 `Dockerfile` | 없음 | API, WebSocket. 힙 1GB |
| postgres | `postgres:17` | 없음 | 원본 데이터. 볼륨 `postgres-data` |
| redis | `redis:7` | 없음 | 토큰 차단 목록, 티켓, 소유 리스, 빈도 제한. 디스크에 남기지 않는다(영속 저장소로 쓰지 않는다) |

노드를 여럿 두는 구성은 `deploy/cluster/` 의 실험 구성(nginx 일관 해시, D-43)을 바탕으로 한다. 운영용으로는 만들지 않았다.

## 직접 할 일 (AWS 기준)

1. EC2 인스턴스를 만든다. 4GB 메모리(예: t3.medium), 디스크 30GB 이상, Ubuntu 24.04. 보안 그룹은 22(관리자 IP 만), 80, 443(TCP, UDP) 을 연다
2. 도메인의 A 레코드를 인스턴스의 탄력적 IP 로 둔다. Caddy 가 처음 뜰 때 이 도메인으로 인증서를 받는다
3. 인스턴스에 Docker 와 Docker Compose 플러그인, JDK 25 를 설치한다(서버 jar 를 그 기기에서 빌드할 때. CI 의 산출물을 옮겨도 된다)
4. 저장소를 받고 jar 를 만든다: `./gradlew :app:bootJar`
5. `cp deploy/prod/.env.example deploy/prod/.env` 하고 값을 채운다. `COPAGE_DB_PASSWORD` 와 `COPAGE_JWT_SECRET` 는 `openssl rand -base64 48` 로 만든다. `.env` 는 저장소에 올리지 않는다(.gitignore)
6. `docker compose -f deploy/prod/compose.yaml --env-file deploy/prod/.env up -d --build`
7. `https://도메인/` 으로 가입해 본다. 로그는 `docker compose -f deploy/prod/compose.yaml logs -f web app`

## 환경 변수

| 이름 | 필수 | 뜻 |
| --- | --- | --- |
| `COPAGE_DOMAIN` | 예 | 화면과 API 의 도메인. 허용 Origin(`https://도메인`)도 이것으로 정한다 |
| `COPAGE_DB_PASSWORD` | 예 | PostgreSQL 비밀번호. 처음 볼륨을 만들 때 정해지고, 바꾸려면 DB 안에서도 바꿔야 한다 |
| `COPAGE_JWT_SECRET` | 예 | Access 토큰 서명 키. 32바이트 이상. 바꾸면 발급한 Access 토큰(최대 15분)이 401 이 되고 화면은 Refresh 쿠키로 다시 받는다 |
| `COPAGE_JAVA_OPTIONS` | 아니요 | 서버 JVM 옵션. 기본 `-Xmx1g` |
| `COPAGE_HTTP_PORT`, `COPAGE_HTTPS_PORT` | 아니요 | 밖으로 여는 포트. 기본 80, 443. 로컬 확인용 |

서버가 받는 다른 설정(시간 한도, 빈도 제한 끄기 `COPAGE_RATE_LIMIT`, 관리 포트 등)은 `app/src/main/resources/application.yml` 에 있다. 비밀 값은 이미지와 저장소에 넣지 않는다.

## 백업

원본은 PostgreSQL 하나다. Redis 는 잃어도 된다(로그인한 사람들이 다시 로그인하고, 열린 편집 연결이 다시 붙는다).

```bash
# 매일 한 번 (cron). S3 로 옮기는 것은 AWS CLI 로 덧붙인다.
docker compose -f deploy/prod/compose.yaml exec -T postgres pg_dump -U copage -Fc copage > copage-$(date +%F).dump
# 되살리기
docker compose -f deploy/prod/compose.yaml exec -T postgres pg_restore -U copage -d copage --clean < copage-2026-11-25.dump
```

## 지표와 상태 확인

관리 포트(8081)는 서버 컨테이너 안의 127.0.0.1 에만 열린다. 밖에서 긁으려면 `COPAGE_MANAGEMENT_ADDRESS=0.0.0.0` 을 주고 Prometheus 를 같은 compose 네트워크에 둔다(관리 포트를 호스트에 열지 않는다). 지표 이름은 [11-TEST-PLAN.md](./11-TEST-PLAN.md) 13.4 에 있다. 이 구성에는 Prometheus 와 에러 모니터링(Sentry 등)을 넣지 않았다.

## 업데이트

`git pull`, `./gradlew :app:bootJar`, `docker compose ... up -d --build`. 서버 한 대라 서버를 바꾸는 동안 몇 초 끊긴다. 열린 편집기는 다시 붙고 보내지 못한 편집은 브라우저 보관(D-42)으로 다시 보낸다. 마이그레이션은 서버가 뜰 때 Flyway 가 돌린다. 끊김 없이 바꾸려면 노드 둘과 계획 종료(D-43)가 있어야 한다.

## 보안 메모

- 접근 로그는 주소의 WebSocket 티켓(`?ticket=`), 공유 링크와 초대의 토큰(`/s/`, `/api/share/`, `/invite/`, `/api/invites/`), Authorization 헤더, 쿠키를 남기지 않는다(SEC-7, D-28)
- 로그인 없이 부를 수 있는 경로는 IP 마다 빈도를 제한한다: 가입 시간당 10번, 로그인 분당 30번, 공유 링크 열기와 티켓 분당 60번, 초대 보기 분당 60번(D-48). 넘으면 429 와 Retry-After
- 서버는 내부 주소(Caddy)가 붙인 X-Forwarded-For 만 믿는다. 서버 포트를 밖에 열면 안 된다
