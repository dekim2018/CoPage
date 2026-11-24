// 부하 도구 (docs/11-TEST-PLAN.md 13.3). 가상 편집자는 ot-core 의 ClientSession 을 그대로 쓰고, 규약 JSON 은 app 의 OpJson 을 쓴다.
// 실행: ./gradlew :loadgen:run --args="scenario=one-doc editors=50 seconds=60"
plugins {
    application
}

dependencies {
    implementation(project(":ot-core"))
    // OpJson 만 쓴다. 스프링 등 app 의 의존성은 끌어오지 않는다.
    implementation(project(":app")) { isTransitive = false }
    // app 의 Spring Boot(4.2.0)가 관리하는 판과 맞춘다. Boot 를 올릴 때 함께 올린다(D-35).
    implementation("tools.jackson.core:jackson-databind:3.1.8")
}

application {
    mainClass.set("com.copage.loadgen.Main")
}
