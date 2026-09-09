# Two stages so the runtime image carries no Maven, no source and no build cache.
# Build for the deployment architecture explicitly when cross-building from x86 to the ARM box:
#   docker buildx build --platform linux/arm64 -f api/Dockerfile -t shush-api:latest .
FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, so a source-only change does not re-download them.
COPY api/pom.xml .
RUN mvn -B -q dependency:go-offline

COPY api/src ./src
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
#
# 60, not 70+: the remainder is not slack, it is metaspace, code cache, thread stacks, GC
# structures and direct buffers. Size the heap to the whole limit and the container gets
# OOM-killed by the kernel, which looks like a crash rather than an out-of-memory error.
#
# This only works if the container HAS a limit -- with none, the percentage is taken against
# the whole host, and every replica sizes itself for the entire machine.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseZGC -XX:+ZGenerational"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
