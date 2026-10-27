# -------- Build stage --------
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Wrapper and build scripts first, so dependency downloads cache apart from source.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon installDist -x test


# -------- Runtime stage --------
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/build/install/brainstorm_profile_resolver ./

# One run, then exit: scheduled by a CronJob.
ENTRYPOINT ["/app/bin/brainstorm_profile_resolver"]
