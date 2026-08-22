# 빌드 단계. 의존성 레이어를 먼저 만들어 두면 소스만 바뀔 때 다시 받지 않는다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# 실행 단계. JDK 가 아니라 JRE 만 담는다.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 루트로 실행하지 않는다.
RUN addgroup -S app && adduser -S -G app app
COPY --from=build --chown=app:app /build/build/libs/*.jar app.jar
USER app

# application.yml 의 기본 프로필이 local 이라 지정하지 않으면 배포 이미지가 localhost 를 바라본다.
# 컴포즈나 배포 매니페스트에서 덮어쓴다.
ENV SPRING_PROFILES_ACTIVE=dev

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
