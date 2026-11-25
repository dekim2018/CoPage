// 서버. 계정(identity), 워크스페이스(workspace), 권한(permission), 협업(collab) 모듈이 있다. 데이터는 PostgreSQL, 토큰과 티켓은 Redis 에 둔다.
plugins {
    java
    id("org.springframework.boot") version "4.2.0"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation(project(":ot-core"))
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // 구글, 깃허브 로그인 (A-4, D-54). 클라이언트 ID 를 주었을 때만 켜진다.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // 초대 메일 (W-1, D-50). 메일 서버를 설정했을 때만 쓴다.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // 지표 (docs/11-TEST-PLAN.md 13.4). Prometheus 형식으로 관리 포트에 내보낸다.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // 에러 모니터링 (D-49). DSN 을 주지 않으면 꺼져 있다. Sentry 와 같은 규약의 GlitchTip 같은 자체 설치형에도 보낼 수 있다.
    implementation("io.sentry:sentry-spring-boot-4-starter:8.65.1")
    // ERROR 로그(세션을 버림, 커밋 실패 등)도 보낸다. 스타터에 들어 있지 않다.
    implementation("io.sentry:sentry-logback:8.65.1")
    // Sentry 의 클래스 파일이 가리키는 주석. 없으면 컴파일 경고가 난다.
    compileOnly("org.jetbrains:annotations:26.1.0")
    testCompileOnly("org.jetbrains:annotations:26.1.0")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(testFixtures(project(":ot-core")))
    // 모듈 경계 검사 (진단 ARCH-1, docs/review/DECISIONS.md D-32)
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testImplementation("org.springframework.boot:spring-boot-starter-websocket-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 연산 JSON 형식이 TypeScript 구현과 같은지 적합성 벡터로 확인하는 테스트가 있다. 벡터를 먼저 만든다.
tasks.test {
    val vectors = project(":ot-core").tasks.named("exportVectors")
    dependsOn(vectors)
    val dir = project(":ot-core").layout.buildDirectory.dir("vectors")
    systemProperty("copage.vectors.dir", dir.get().asFile.absolutePath)
    // 측정용 시험(SnapshotIntervalBench 등)은 -Dcopage.bench=true 일 때만 돈다.
    System.getProperty("copage.bench")?.let { systemProperty("copage.bench", it) }
}

// ErrDeck(같은 사람이 만든 모니터링 서비스) SDK (D-53). 에러, 성능 추적, 지표를 ErrDeck 으로 보낸다. 패키지가 CI 에 없으므로
// -Perrdeck=true 로 빌드할 때만 넣는다. 먼저 ErrDeck 저장소에서 ./gradlew :core:publishToMavenLocal :sdk-spring:publishToMavenLocal
// 넣지 않고 빌드하면 application.yml 의 errdeck 설정은 아무도 읽지 않는다.
if (providers.gradleProperty("errdeck").orNull == "true") {
    repositories {
        mavenLocal()
    }
    dependencies {
        implementation("com.errdeck:errdeck-sdk-spring:0.1.0-SNAPSHOT")
    }
}

