# 서버 이미지. 먼저 jar 를 만든다: ./gradlew :app:bootJar
# 설정은 환경 변수로 준다(docs/README 의 환경 변수 표, application.yml). 비밀 값은 이미지에 넣지 않는다.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY app/build/libs/app-0.1.0-SNAPSHOT.jar app.jar
# API 포트. 관리 포트(8081)는 COPAGE_MANAGEMENT_ADDRESS 를 열 때만 밖에서 닿는다.
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
