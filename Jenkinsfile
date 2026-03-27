// ============================================================================
// PatchPilot — Jenkins Pipeline
//
// Pipeline flow:
//   GitHub push → Checkout → Build & Test → SonarQube Scan → Publish to Artifactory
//
// Prerequisites (configure in Jenkins before running):
//   1. Global Tool Configuration:
//        Maven:  name = "Maven-3.9",  install from Apache automatically
//        JDK:    name = "JDK-21",     install from Adoptium automatically
//   2. Configure System → SonarQube servers:
//        Name = "SonarQube", URL = http://sonarqube:9000
//        Add SonarQube token as a "Secret text" credential, then select it here
//   3. Credentials (Manage Jenkins → Credentials → Global):
//        ID = "artifactory-credentials", type = Username/Password
//        Username: admin, Password: <Artifactory admin password from .env>
//   4. GitHub webhook:
//        In GitHub repo → Settings → Webhooks → Add webhook
//        Payload URL: http://<jenkins-host>:8080/github-webhook/
//        Content type: application/json, trigger: Just the push event
// ============================================================================

pipeline {

    // 'any' runs on the built-in Jenkins node.
    // For larger setups, replace with a Docker agent or a dedicated build node.
    agent any

    tools {
        // Tool name must match exactly what is configured in Jenkins
        // Global Tool Configuration → Maven installations.
        // JDK is not declared here — the jenkins/jenkins:lts-jdk21 image
        // ships with JDK 21 already on PATH, so no tool installation is needed.
        maven 'Maven-3.9'
    }

    environment {
        // Limit JVM heap for the Maven process itself (not the compiled app).
        // Prevents OOM on a developer laptop running the full Docker stack.
        MAVEN_OPTS = '-Xmx512m -XX:MaxMetaspaceSize=256m'
    }

    stages {

        // --------------------------------------------------------------------
        // Stage 1: Checkout
        // Clones the repository from the URL configured in the Jenkins job.
        // 'checkout scm' uses the SCM settings from the pipeline job definition
        // — no hardcoded URL needed here.
        // --------------------------------------------------------------------
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        // --------------------------------------------------------------------
        // Stage 2: Build & Test
        //
        // mvn verify runs the full lifecycle up to and including integration
        // tests: compile → test → package → verify. This also runs SpotBugs
        // and the OWASP Dependency Check (configured in the parent POM).
        //
        // -B: batch mode — suppresses interactive prompts, cleaner logs in CI.
        // --------------------------------------------------------------------
        stage('Build & Test') {
            steps {
                sh 'mvn -pl demo-app verify -B'
            }
            // Note: JUnit test result publishing requires the JUnit plugin.
            // Install it from Manage Jenkins → Plugins if test trend graphs are needed.
        }

        // --------------------------------------------------------------------
        // Stage 3: SonarQube Analysis
        //
        // withSonarQubeEnv injects SONAR_HOST_URL and SONAR_AUTH_TOKEN into
        // the environment — these are read automatically by the sonar-maven-plugin.
        // The string "SonarQube" must match the server name in Jenkins Configure System.
        //
        // After the scan, SonarQube fires a webhook to the AI Agent:
        //   POST http://ai-agent:8081/api/webhooks/sonarqube
        // The AI Agent processes findings asynchronously — no need to wait here.
        //
        // Note: sonar.branch.name requires SonarQube Developer Edition or above.
        // Community Edition (used here) analyzes a single branch only.
        // --------------------------------------------------------------------
        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQube') {
                    sh 'mvn -f demo-app/pom.xml sonar:sonar -B'
                }
            }
        }

        // --------------------------------------------------------------------
        // Stage 4: Publish to Artifactory
        //
        // mvn deploy uploads the JAR to the Artifactory Maven repository.
        // Credentials are injected via withCredentials — they are never written
        // to disk in plain text and do not appear in the console log.
        //
        // A temporary Maven settings.xml is written to the workspace, used for
        // the deploy, then deleted in the post block.
        //
        // -DskipTests: tests already ran in Stage 2; no need to repeat.
        // --------------------------------------------------------------------
        stage('Publish to Artifactory') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'artifactory-credentials',
                    usernameVariable: 'ART_USER',
                    passwordVariable: 'ART_PASS' // pragma: allowlist secret
                )]) {
                    // Write a minimal Maven settings.xml with the Artifactory
                    // server credentials. The server IDs must match the
                    // <distributionManagement> IDs in demo-app/pom.xml.
                    writeFile file: 'maven-settings.xml', text: """<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <servers>
    <server>
      <id>artifactory-releases</id>
      <username>${env.ART_USER}</username>
      <password>${env.ART_PASS}</password>
    </server>
    <server>
      <id>artifactory-snapshots</id>
      <username>${env.ART_USER}</username>
      <password>${env.ART_PASS}</password>
    </server>
  </servers>
</settings>
"""
                    sh 'mvn -pl demo-app deploy -B -DskipTests -s maven-settings.xml'
                }
            }
            post {
                always {
                    // Remove the settings file so credentials do not linger on disk
                    sh 'rm -f maven-settings.xml'
                }
            }
        }

        // --------------------------------------------------------------------
        // Stage 5: Deploy to Azure  (Week 3 — uncomment on Day 20)
        //
        // Prerequisites before uncommenting:
        //   - infrastructure/azure/main.bicep deployed
        //   - ACR_NAME, AZURE_RESOURCE_GROUP, CONTAINER_APP_NAME set in Jenkins
        //     environment or as credentials
        //   - Jenkins ACI has Managed Identity with AcrPush + ContainerApp roles
        // --------------------------------------------------------------------
        // stage('Deploy to Azure') {
        //     when {
        //         // Only deploy from the main branch, not from feature branches
        //         branch 'main'
        //     }
        //     steps {
        //         sh '''
        //             az acr login --name $ACR_NAME
        //             docker build -f ai-agent/Dockerfile -t $ACR_LOGIN_SERVER/ai-agent:$BUILD_NUMBER ai-agent/
        //             docker push $ACR_LOGIN_SERVER/ai-agent:$BUILD_NUMBER
        //             az containerapp update \
        //                 --name $CONTAINER_APP_NAME \
        //                 --resource-group $AZURE_RESOURCE_GROUP \
        //                 --image $ACR_LOGIN_SERVER/ai-agent:$BUILD_NUMBER
        //         '''
        //     }
        // }

    }

    // ------------------------------------------------------------------------
    // Post-pipeline notifications
    // 'always' runs regardless of outcome; 'failure' only on failure.
    // ------------------------------------------------------------------------
    post {
        success {
            echo "Build ${env.BUILD_NUMBER} succeeded. JAR published to Artifactory."
        }
        failure {
            echo "Build ${env.BUILD_NUMBER} failed. Check the stage logs above."
        }
    }
}
