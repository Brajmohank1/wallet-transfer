# syntax=docker/dockerfile:1
# Debian-based tags, not alpine: Temurin's alpine images are amd64-only.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package
RUN mv target/wallet-transfer-*.jar /app.jar

FROM eclipse-temurin:17-jre AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/* \
 && addgroup --system app && adduser --system --ingroup app app
COPY --from=build /app.jar /app.jar
USER app

ENV PORT=8080
EXPOSE 8080

# Generous start-period for the JVM's slower cold boot; SerialGC + a
# capped heap keep the footprint small on a free-tier instance.
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=60", "-Xss256k", "-jar", "/app.jar"]
