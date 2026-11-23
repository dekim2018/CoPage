// 모든 모듈에 공통인 설정. 모듈별 의존성은 각 모듈의 build.gradle.kts 에 둔다.
subprojects {
    group = "com.copage"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.add("-Xlint:all")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // 무작위 테스트를 오래 돌릴 때: ./gradlew :ot-core:test -Dcopage.ot.scale=100
            System.getProperty("copage.ot.scale")?.let { systemProperty("copage.ot.scale", it) }
            testLogging {
                events("failed")
                showStandardStreams = true
            }
        }
    }
}
