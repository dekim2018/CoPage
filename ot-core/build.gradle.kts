// 문서 모델과 연산 변환. 외부 의존성 없이 JDK 만으로 동작한다.
plugins {
    `java-library`
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 적합성 벡터를 JSON 으로 내보낼 때만 쓴다. 버전은 app 의 Spring Boot 4.1.1 이 쓰는 것과 맞췄다.
    testImplementation("tools.jackson.core:jackson-databind:3.1.5")
}

// TypeScript 구현과 맞춰 볼 적합성 벡터를 build/vectors 에 만든다. frontend 의 Vitest 가 이 파일을 읽는다.
val exportVectors by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Java 구현으로 계산한 적합성 벡터를 build/vectors 에 내보낸다"
    val outputDir = layout.buildDirectory.dir("vectors")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.copage.ot.vectors.VectorExporter")
    argumentProviders.add(CommandLineArgumentProvider { listOf(outputDir.get().asFile.absolutePath) })
    outputs.dir(outputDir)
}
