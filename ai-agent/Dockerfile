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

# Install Node.js + npm so we can install the claude CLI.
# nodejs/npm come from the Alpine package registry; no extra repo needed.
# The claude CLI is only used when CLAUDE_MODE=max — it has no effect when
# CLAUDE_MODE=api, so this layer adds ~80 MB but causes no harm in api mode.
RUN apk add --no-cache nodejs npm && \
    npm install -g @anthropic-ai/claude-code

WORKDIR /app

ARG JAR_FILE=target/ai-agent-*.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
