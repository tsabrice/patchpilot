# ============================================================================
# PatchPilot AI Agent — Dockerfile
#
# Runtime-only image. The JAR must be built first by Maven:
#   mvn -f ai-agent/pom.xml package -DskipTests
#
# Build the image from the project root:
#   docker build -f ai-agent/Dockerfile -t patchpilot-ai-agent ai-agent/
# ============================================================================

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

ARG JAR_FILE=target/ai-agent-*.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
