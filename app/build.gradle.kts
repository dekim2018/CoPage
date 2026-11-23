// 협업 서버. 지금은 문서 세션과 WebSocket 규약(collab)만 있다.
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation(project(":ot-core"))
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-websocket-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 연산 JSON 형식이 TypeScript 구현과 같은지 적합성 벡터로 확인하는 테스트가 있다. 벡터를 먼저 만든다.
tasks.test {
    val vectors = project(":ot-core").tasks.named("exportVectors")
    dependsOn(vectors)
    val dir = project(":ot-core").layout.buildDirectory.dir("vectors")
    systemProperty("copage.vectors.dir", dir.get().asFile.absolutePath)
}
