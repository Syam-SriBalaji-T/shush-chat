# Two stages so the runtime image carries no Maven, no source and no build cache.
# Build for the deployment architecture explicitly when cross-building from x86 to the ARM box:
#   docker buildx build --platform linux/arm64 -t shush-api:latest api
FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, so a source-only change does not re-download them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Never run as root; nothing here needs it.
RUN addgroup -S shush && adduser -S -G shush shush
COPY --from=build /build/target/*.jar app.jar
USER shush

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the same image runs on a 2 GB ARM box and on the
# resized benchmark instance, and the heap should follow the container limit either way.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:+ZGenerational"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
