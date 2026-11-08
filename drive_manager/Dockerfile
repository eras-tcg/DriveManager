# Built from the repository root (the game's engine lives next to the server):
#   docker build -f drive_manager/Dockerfile .

# Stage 1: the game's engine (Kotlin Multiplatform, ../eras-engine) into the local Maven repository
FROM eclipse-temurin:21-jdk AS engine
WORKDIR /build/eras-engine
COPY eras-engine/gradlew eras-engine/settings.gradle.kts eras-engine/build.gradle.kts eras-engine/gradle.properties ./
COPY eras-engine/gradle ./gradle
# Gradle itself, apart, so a change to the engine's code does not download it again
RUN ./gradlew --no-daemon --version -q
COPY eras-engine/kotlin-js-store ./kotlin-js-store
COPY eras-engine/src ./src
# The card files (the one copy of the cards' data): the jar carries them and their bundle
COPY eras-engine/cards ./cards
RUN ./gradlew --no-daemon publishToMavenLocal

# Stage 2: the server, with the engine's artifact (com.eras:eras-engine-jvm) from stage 1
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY --from=engine /root/.m2/repository/com/eras /root/.m2/repository/com/eras
COPY drive_manager/pom.xml .
RUN mvn dependency:go-offline -q
COPY drive_manager/src ./src
RUN mvn package -DskipTests -q

# Stage 3: Run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
