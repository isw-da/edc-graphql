# GraphQL EDC Connector for Simba Intelligence

A custom Enterprise Data Connector (EDC) that connects any GraphQL API to
[Simba Intelligence](https://insightsoftware.com/simba-intelligence/) and
Logi Composer for natural language querying.

Built from the [Zoomdata EDC template](https://github.com/Zoomdata/edc-cratedb),
upgraded to production-compatible stack (edc-api 25.4.0, Thrift 0.21.0,
Spring Boot 3.2.5, Java 17).

## What it does

- Connects to **any GraphQL API** via HTTP POST
- Auto-discovers schema via GraphQL introspection
- Supports both **flat array** and **Relay connection** patterns (edges/node)
- Configurable authentication (Bearer, API key, custom headers)
- Registers with Composer via Consul for seamless integration
- Queries through SI Playground using natural language

## Tested against

| API | Pattern | Status |
|-----|---------|--------|
| [Countries API](https://countries.trevorblades.com/graphql) | Flat array, no auth | Working |
| Supabase FRC (fintech risk/compliance) | Relay, apikey header | Working |
| Supabase Factory (manufacturing ops) | Relay, apikey header | Working |

## Quick start

### Prerequisites

- Java 17
- Maven 3.x
- Docker (for containerised deployment)
- A running Simba Intelligence instance ([setup guide](https://github.com/isw-da/simba-intelligence-skill))

### Build

```bash
export JAVA_HOME=/path/to/jdk-17
mvn clean package -Dlicense.skip=true -DskipTests
```

### Run locally

```bash
java -Duser.timezone=UTC -jar target/connector-server-graphql-1.0.0-exec.jar
```

Server starts on port 7338 at `/connector/`.

### Deploy to Kubernetes

```bash
# Build and load Docker image
docker build -t edc-graphql:latest .
# For kind:
kind load docker-image edc-graphql:latest --name <cluster>
# For cloud: push to your container registry

# Deploy pod and service
kubectl apply -n <namespace> -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: edc-graphql
  labels:
    app: edc-graphql
spec:
  containers:
  - name: edc-graphql
    image: edc-graphql:latest
    imagePullPolicy: Never
    ports:
    - containerPort: 7338
---
apiVersion: v1
kind: Service
metadata:
  name: edc-graphql
spec:
  selector:
    app: edc-graphql
  ports:
  - port: 7338
    targetPort: 7338
EOF
```

### Register in Composer

```bash
# 1. Register in Consul
kubectl -n <namespace> exec <release>-consul-server-0 -c consul -- \
  consul services register \
    -name=edc-graphql \
    -address=edc-graphql.<namespace>.svc.cluster.local \
    -port=7338

# 2. Register in Composer
curl -s -X POST "http://localhost:8080/discovery/api/connectors" \
  -u "admin:<password>" \
  -H "Content-Type: application/vnd.composer.v3+json" \
  -d '{
    "name": "GraphQL",
    "type": "DISCOVERY",
    "params": {
      "SERVICE_NAME": "edc-graphql",
      "BEHIND_GATEWAY": "false"
    }
  }'
```

### Create a connection

In the SI UI: **Connections > Create > GraphQL**

| Parameter | Required | Description |
|-----------|----------|-------------|
| GRAPHQL_URL | Yes | GraphQL endpoint URL |
| AUTH_TOKEN | No | Authentication token |
| AUTH_HEADER_NAME | No | Header name (default: Authorization) |
| AUTH_HEADER_PREFIX | No | Token prefix (default: Bearer) |
| CUSTOM_HEADERS | No | Additional headers as JSON, e.g. `{"apikey":"eyJ..."}` |

## Connection examples

### Public API (no auth)
- **URL:** `https://countries.trevorblades.com/graphql`
- Leave all auth fields empty

### Supabase GraphQL
- **URL:** `https://<project-ref>.supabase.co/graphql/v1`
- **CUSTOM_HEADERS:** `{"apikey":"<your-supabase-anon-key>"}`

## Test suites

```bash
# Infrastructure + API tests (66 tests)
bash test-suite.sh

# Comprehensive NLQ query tests (58 queries)
bash test-nlq-severe.sh

# Infrastructure + schema + security tests (66 tests)
bash test-suite-v2.sh
```

## Architecture

```
Composer QE  -->  Thrift RPC  -->  GraphQL EDC  -->  HTTP POST  -->  GraphQL API
                  /connector/      (this repo)       with auth       (any endpoint)
```

The connector implements `ConnectorService.Iface` (Thrift) and translates
EDC requests into GraphQL queries. Schema discovery uses GraphQL
introspection. Relay connection patterns (edges/node) are auto-detected
and handled transparently.

## Related

- **[SI Setup Skill](https://github.com/isw-da/simba-intelligence-skill)** —
  Install, configure, and troubleshoot Simba Intelligence. Includes a
  comprehensive guide for building custom EDC connectors at
  `references/custom-edc-build.md`.

## Known limitations

- **Supabase pagination:** Default 30 rows per page. Large tables return
  partial data without explicit `first: N` arguments.
- **DATE fields:** Mapped as STRING to avoid QE min/max statistics
  requirement. Time-series queries not supported.
- **No pushdown filtering:** All data fetched then filtered by the QE.
  Fine for small datasets, may be slow for large ones.
