# ============================================================================
# PatchPilot Demo App — Dockerfile
#
# This is a runtime-only image. The JAR must be built first by Maven:
#   mvn -pl demo-app package -DskipTests
#
# Then build the image from the project root:
#   docker build -f demo-app/Dockerfile -t patchpilot-demo-app demo-app/
# ============================================================================

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# JAR produced by: mvn package
ARG JAR_FILE=target/demo-app-*.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
