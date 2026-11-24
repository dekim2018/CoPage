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
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // 지표 (docs/11-TEST-PLAN.md 13.4). Prometheus 형식으로 관리 포트에 내보낸다.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
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
}
