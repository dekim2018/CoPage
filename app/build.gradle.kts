// 협업 서버. 지금은 협업 모듈(collab)과 계정 모듈(identity)이 있다. 연산 로그와 사용자는 PostgreSQL, 토큰과 티켓은 Redis 에 둔다.
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
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
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-websocket-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 연산 JSON 형식이 TypeScript 구현과 같은지 적합성 벡터로 확인하는 테스트가 있다. 벡터를 먼저 만든다.
tasks.test {
    val vectors = project(":ot-core").tasks.named("exportVectors")
    dependsOn(vectors)
    val dir = project(":ot-core").layout.buildDirectory.dir("vectors")
    systemProperty("copage.vectors.dir", dir.get().asFile.absolutePath)
}
