// 부하 도구. ot-core 의 ClientSession 을 그대로 쓰는 가상 클라이언트를 6단계(측정과 개선)에서 만든다.
plugins {
    java
}

dependencies {
    implementation(project(":ot-core"))
}
