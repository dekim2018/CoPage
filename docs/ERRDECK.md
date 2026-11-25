# ErrDeck 으로 모니터링(로컬)

같은 사람이 만든 모니터링 서비스 [ErrDeck](https://github.com/dekim2018/ErrDeck) 에 에러, 성능 추적, 지표를 보낼 수 있다(D-53). 기본으로는 꺼져 있고 CI 는 SDK 없이 빌드한다. ErrDeck 저장소가 같은 상위 폴더(`../ErrDeck`)에 있다고 본다.

```bash
# ErrDeck 쪽: SDK 를 로컬 메이븐에 넣고, ErrDeck 을 띄우고, CoPage 프로젝트와 DSN 을 만든다
(cd ../ErrDeck && ./gradlew :core:publishToMavenLocal :sdk-spring:publishToMavenLocal \
  && docker compose --profile core --profile obs up -d --build \
  && docker compose exec -T postgres psql -U errdeck < scripts/seed/systems.sql \
  && docker compose exec -T postgres psql -U errdeck < scripts/seed/copage-local.sql)   # 마지막 출력이 DSN 두 개

# 서버: SDK 를 넣어 빌드하고 DSN 을 준다. 관리 포트를 0.0.0.0 으로 열어야 ErrDeck 의 프로메테우스(도커 안)가 지표를 긁는다
./gradlew -Perrdeck=true :app:bootJar
ERRDECK_DSN=http://{키}@localhost:18080/{copage-app 프로젝트} COPAGE_MANAGEMENT_ADDRESS=0.0.0.0 COPAGE_JWT_SECRET=... \
  java -jar app/build/libs/app-0.1.0-SNAPSHOT.jar

# 화면
cd frontend && VITE_ERRDECK_DSN=http://{키}@localhost:18080/{copage-web 프로젝트} npm run dev
```

ErrDeck 콘솔(`../ErrDeck/web` 의 `npm run dev`, 5173)의 전체 보기에 CoPage 의 이슈와 요약 카드가, 성능 화면에 화면의 페이지 이동부터 서버 요청까지 이어진 추적이 보인다. 화면 SDK 를 고쳤으면 `npm run errdeck:sync` 로 다시 가져온다. e2e 가 5174 를 쓰므로 확인용 화면은 다른 포트로 띄운다.

