# ALFHA ModelHub

Java backend for a future multi-model AI orchestration and evaluation platform.
This repository is independent of ALFHA Agent.

## Current scope: Milestone 1

Implemented: Spring Boot application, JSON health endpoint, Java 21 Maven build,
and an automated HTTP test. AI providers, generation, comparison, PostgreSQL,
Docker Compose, and CI belong to later milestones and are not implemented yet.

## Prerequisites

- JDK 21 (`JAVA_HOME` must point to the JDK directory).
- Apache Maven 3.6.3 or newer (verified locally with 3.9.12).
- Internet access for the initial Maven dependency download.

No database, Ollama instance, cloud account, or API key is needed for Milestone 1.
Spring Boot is pinned to 4.1.2; Java compilation targets release 21.

Check your environment:

```shell
java -version
mvn -version
```

For PowerShell, select an installed JDK for the current session:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

This development workspace also has a local Temurin JDK under the ignored
`.tools/jdk21` directory. To use it in this checkout:

```powershell
$env:JAVA_HOME = (Get-ChildItem '.tools\jdk21' -Directory | Select-Object -First 1).FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## Build and test

```shell
mvn clean verify
```

To keep Maven's dependency cache inside this checkout (as used during setup):

```shell
mvn "-Dmaven.repo.local=.m2/repository" --batch-mode --no-transfer-progress clean verify
```

The JUnit 5 test starts the real Spring application on a random port, sends an
HTTP request, verifies HTTP 200, JSON content type, and the exact response fields,
then closes the application. It uses no mocks or external services.
JUnit 5 is explicitly selected to meet the project requirements. Because Spring
Framework 7's JUnit extension requires JUnit 6, the test manages the application
context directly rather than using `@SpringBootTest`.
Reports are written to `target/surefire-reports`.

## Run

```shell
mvn spring-boot:run
```

Or run the packaged application after building:

```shell
java -jar target/modelhub-0.0.1-SNAPSHOT.jar
```

If you use the local Maven cache, include `"-Dmaven.repo.local=.m2/repository"`
in the Maven run command as well.

The default port is 8080. Override it with `SERVER_PORT`, for example in PowerShell:

```powershell
$env:SERVER_PORT = '8081'
java -jar target/modelhub-0.0.1-SNAPSHOT.jar
```

## Health API

```shell
curl http://localhost:8080/api/health
```

PowerShell alternative:

```powershell
Invoke-RestMethod http://localhost:8080/api/health
```

HTTP 200, `Content-Type: application/json`:

```json
{"status":"UP","application":"ALFHA ModelHub"}
```

This endpoint reports application liveness. It does not check AI providers or a
database. Default error responses exclude exception details and stack traces.

## Architecture

- `vn.alfha.modelhub.ModelHubApplication`: application entry point and component scan root.
- `controller.HealthController`: HTTP routing.
- `dto.HealthResponse`: immutable response record.
- `src/main/resources/application.properties`: application name, environment-based port, error settings.
- `src/test/java/vn/alfha/modelhub/HealthEndpointTest.java`: real HTTP integration test.

The foundation uses Spring MVC with embedded Tomcat. No service is necessary for
a static health response. Later milestones will introduce `service`, `provider`,
`config`, `exception`, `entity`, and `repository` packages as their behavior is
implemented; controllers will delegate orchestration to services and external
communication to provider adapters.

## Development workflow

Keep `.env` files, credentials, downloaded tools, dependency caches, and build
outputs out of Git. Make local commits after tested milestones. Remote pushes
require explicit approval.

Next: Milestone 2 — add the provider interface, Ollama adapter, `/api/generate`,
timeouts, connection failure handling, and mocked provider tests. Real Ollama
integration must be verified separately and reported explicitly.
