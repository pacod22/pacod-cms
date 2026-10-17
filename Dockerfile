# Shared multi-stage build for both applications.
#
# Which application is built is chosen by the MODULE build argument, so every application
# service in docker-compose.yml uses this one file:
#
#   docker build --build-arg MODULE=pacod-cms-site .
#   docker build --build-arg MODULE=pacod-cms-studio .
#
# Three rules this file keeps:
#
#  1. An exec-form ENTRYPOINT (a JSON array) performs NO variable substitution of its own:
#     "-Dspring.profiles.active=${PROFILE}" handed straight to java would reach the JVM as that
#     literal string, activate a profile that does not exist, and fail the context on the first
#     @Value it cannot resolve. Configuration comes from environment variables that Spring Boot
#     binds natively (SPRING_PROFILES_ACTIVE, SERVER_PORT), so there is nothing to substitute;
#     the one variable left, JAVA_OPTS, is expanded by the `sh -c` at the end.
#  2. Multi-stage: the jar is built inside the image, so `docker compose up --build` works on a
#     clean checkout, with nothing built beforehand.
#  3. No port baked in at build time: SERVER_PORT is set per service, so one image serves any.

# ---------------------------------------------------------------- build stage
# Not Alpine: the Maven wrapper downloads its distribution and needs a full curl/tar.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src

# Copy the wrapper and every pom first so dependency resolution is cached independently of
# source changes. Editing a .java file then does not re-resolve the whole dependency tree.
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
COPY pacod-cms-core/pom.xml pacod-cms-core/
COPY pacod-cms-site/pom.xml pacod-cms-site/
COPY pacod-cms-studio/pom.xml pacod-cms-studio/

RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q dependency:go-offline -DskipTests || true

COPY pacod-cms-core pacod-cms-core
COPY pacod-cms-site pacod-cms-site
# ~1.9MB of vendored TinyMCE rides along here. It is only in the studio's jar, but every
# build sees it in the context -- which is why the source COPY comes after the pom layer.
COPY pacod-cms-studio pacod-cms-studio

ARG MODULE=pacod-cms-site
# -Dmaven.test.skip, not -DskipTests: on Spring Boot 4.1 the latter does not skip AOT.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B package -Dmaven.test.skip -pl "${MODULE}" -am \
 && cp "${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar" /src/app.jar

# ---------------------------------------------------------------- runtime stage
FROM eclipse-temurin:25-jre-alpine AS runtime

# Run unprivileged. The base images run as root by default.
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build --chown=app:app /src/app.jar /app/app.jar

# Written to by the application; created here so they exist even without a bind mount.
RUN mkdir -p /data/logs /data/storage /app/config \
 && chown -R app:app /data /app

USER app

# Container-aware heap sizing; the JVM otherwise sizes against the host, not the cgroup limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Documentation only: SERVER_PORT is set per service in docker-compose.yml (site 8443, studio 7443).
EXPOSE 8443 7443

# Exec form so the JVM is PID 1 and receives SIGTERM directly for a clean shutdown.
# JAVA_OPTS is deliberately word-split by the shell, hence "sh -c".
# No -Djava.security.egd=file:/dev/./urandom: it addressed a JDK 7/8-era SecureRandom stall and
# does nothing on a modern JVM.
# Arguments after the image name reach the application ("$@"): `make site-create` runs the
# studio image with --create-site and the website's details.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
