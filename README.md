# GPS Distance Reconstruction Engine

A backend service built with Spring Boot 3.5.x and Java 21 to reconstruct trusted, explainable, and audit-proof billable distances for cab vendor trips from noisy GPS streams, raw employee pickup/drop milestones, and office anchor constraints.

## How to Run

To run the application locally on port 8081 (configured to avoid conflicts with system services like Oracle TNSLSNR on 8080):

```powershell
.\mvnw.cmd spring-boot:run
```

Once running, verify the health status endpoint:

```powershell
curl.exe http://localhost:8081/api/v1/hello
```

Expected output:
```json
{"status":"UP","service":"gps-distance-engine"}
```

## How to Test

To run automated unit and slicing tests:

```powershell
.\mvnw.cmd test
```

## Status: Batch 1

- Initial Spring Boot 3.5.3 Maven project with Java 21.
- Package skeleton structured according to architectural layers.
- Verified `/api/v1/hello` endpoint with a `@WebMvcTest`.
