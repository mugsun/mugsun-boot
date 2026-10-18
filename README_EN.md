![mugsun-boot](https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/banners/repos/mugsun-boot-en.png)

# mugsun-boot

[简体中文](./README.md) | English

This repository is the mugsun mid-office monolithic backend. Infrastructure is provided by Docker Compose; the application is served as an executable JAR. System administration and workflow live in the core module. GIS, analytics, and AI capabilities are assembled as optional modules.

[Documentation](https://docs.mugsun.com/en/boot/) · [Getting started](https://docs.mugsun.com/en/guide/getting-started) · [Security checklist](https://docs.mugsun.com/en/boot/security-checklist)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)

## Overview

| Item | Detail |
| --- | --- |
| Coordinates | `com.mugsun:mugsun-boot:0.1.0` |
| Runtime | JDK 21; Maven 3.9+ |
| Framework | Spring Boot 3.5.13 |
| Data stores | PostgreSQL 16 (PostGIS + pgvector); Redis 7 (default database index `3`) |
| Dependency | [mugsun-core](https://github.com/mugsun/mugsun-core) `com.mugsun:mugsun-*:0.1.0` (published to [Maven Central](https://repo1.maven.org/maven2/com/mugsun/)) |
| License | [MIT](./LICENSE) |
| Docs | [docs.mugsun.com/en/boot](https://docs.mugsun.com/en/boot/) |

Stack (per current POM): MyBatis-Flex, Sa-Token, JetCache, Warm-Flow, Flyway, Spring AI 1.1.2 (AI module).

## Deployment

Prerequisites: JDK 21, Maven 3.9+, Docker. `mugsun-core` 0.1.0 is on Maven Central and is resolved automatically; a sibling clone is not required.

The first `docker compose up -d` builds the database image locally (`postgis/postgis:16-3.4` + pgvector). The official PostGIS image is amd64 only, so on Apple Silicon it runs under emulation by default: the first build takes several extra minutes and the database is slower. To run natively, switch to a multi-arch base image:

```bash
MUGSUN_PG_BASE_IMAGE=imresamu/postgis:16-3.4 MUGSUN_PG_PLATFORM=linux/arm64 docker compose up -d --build
```

```bash
docker compose up -d
cp config/application-local.yml.example config/application-local.yml
mvn -pl mugsun-boot-server -am package -DskipTests
java -jar mugsun-boot-server/target/mugsun-boot.jar
```

Run `java -jar` from the repository root, otherwise `config/application-local.yml` is not picked up. For development you can optionally run `mvn -pl mugsun-boot-server -am spring-boot:run` from the repository root instead.

**Local install (offline or when Central has not synced):** clone [mugsun-core](https://github.com/mugsun/mugsun-core) as a sibling, run `cd ../mugsun-core && mvn clean install -DskipTests`, then return to this repository to build.

Health check: http://localhost:8080/ping

Default initial account: `admin` / `123456`. The code reads `mugsun.security.init-password`, falling back to the `MUGSUN_INIT_PASSWORD` environment variable (see `SecurityPolicyService`). It is deliberately absent from `application.yml`: the prod startup guard uses its presence to tell whether a password was injected. Production deployments must change the password. The `prod` profile refuses to start without an injected initial password.

Admin console: [mugsun-pc](https://github.com/mugsun/mugsun-pc) (development default port `3006`, proxied to this service on `8080`). Walkthrough: [Documentation · Getting started](https://docs.mugsun.com/en/guide/getting-started).

Package artifact: `mugsun-boot-server/target/mugsun-boot.jar`.

Compose ports: `5432` (PostgreSQL), `6379` (Redis). If a local PostgreSQL / Redis already uses these ports, pick other ports and give the backend the same variables:

```bash
export MUGSUN_DB_PORT=55432 MUGSUN_REDIS_PORT=56379
docker compose up -d
java -jar mugsun-boot-server/target/mugsun-boot.jar
```

Backend environment variables: `MUGSUN_DB_HOST`, `MUGSUN_DB_PORT`, `MUGSUN_DB_NAME`, `MUGSUN_DB_USERNAME`, `MUGSUN_DB_PASSWORD` (or a full `MUGSUN_DB_URL`), `MUGSUN_REDIS_HOST`, `MUGSUN_REDIS_PORT`. The track database `mugsun_track` follows the same host and port by default and can still be overridden with `TRACK_JDBC_URL`. The `prod` profile ignores these and keeps using `JDBC_URL`, `REDIS_HOST`, etc. Development database credentials default to the JDBC settings (`mugsun` / `mugsun`). Without Docker, see `scripts/init-db.sql`.

Local configuration path: `optional:file:./config/application-local.yml` (gitignored). The working directory must be the repository root. Production injection: [Configuration](https://docs.mugsun.com/en/boot/configuration).

OpenAPI: http://localhost:8080/swagger-ui/index.html (disabled under `prod`).

## Composition

<p align="center">
  <img src="https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/arch/boot-iso-en.jpg" alt="Architecture diagram" width="100%" />
</p>

```text
mugsun-boot/
  mugsun-boot-core/     # auth, system, workflow, tenant, open platform, …
  mugsun-boot-gis/      # optional: layers, spatial queries, vector tiles
  mugsun-boot-track/    # optional: ingest, aggregation, replay-related APIs
  mugsun-boot-ai/       # optional: models, chat, knowledge base, MCP (Spring AI)
  mugsun-boot-server/   # assembly shell; default profile = full
```

Maven profiles:

| Profile | Description |
| --- | --- |
| `full` (default) | Assembles core + gis + track + ai |
| `basic` | Core only; disable full: `-Pbasic,'!full'` |
| `gis-only` / `track-only` / `ai-only` | Core + one optional module; disable full |
| `kingbase` / `dameng` | Domestic database drivers |

Runtime flags (classes may still be on the classpath):

- `MUGSUN_GIS_ENABLED=false`
- `MUGSUN_TRACK_ENABLED=false`
- `MUGSUN_AI_ENABLED=false`

Module assembly notes: [docs/可选模块装配.md](docs/可选模块装配.md) (Chinese).

## Scope

**Core module (`mugsun-boot-core`)**

- Authentication and authorization: users, roles, menus, departments, data scope; login and operation audit
- Multi-tenancy, messaging, file storage, code generation, online forms
- Workflow (Warm-Flow)
- Open platform: OAuth2 (including PKCE), API Key
- Transport and audit paths include national-cryptography related capabilities; review the [security checklist](https://docs.mugsun.com/en/boot/security-checklist) before production

**Optional modules**

- `mugsun-boot-track`: `/track/collect`, config delivery, and analytics APIs; forms a vertical slice with the browser SDK and admin console
- `mugsun-boot-gis`: spatial data and vector tiles (requires PostGIS)
- `mugsun-boot-ai`: models, chat, knowledge base, and MCP (Spring AI)

**Runtime conventions**

- PowerJob Worker is disabled by default; enable only with a separate Server and `POWERJOB_ENABLED=true`
- Demo credentials are for local use only; `fronttest` lab seeding requires an explicit switch and is off by default

**Not provided**

- An open-sourced microservice `cloud` distribution
- A publicly accessible demo environment (see [Demo](https://docs.mugsun.com/en/demo))

APIs and configuration keys are defined in source and documentation.

## Related projects

Open-source line: `boot` · `core` · `pc` · `track` · [mugsun-docs](https://github.com/mugsun/mugsun-docs).

| Repository | Relationship |
| --- | --- |
| [mugsun-core](https://github.com/mugsun/mugsun-core) | BOM and starters; pulled from Maven Central by default; local install is an offline / development fallback |
| [mugsun-pc](https://github.com/mugsun/mugsun-pc) | Admin UI; CORS / OAuth redirect default `MUGSUN_FRONT_URL=http://localhost:3006` |
| [mugsun-track](https://github.com/mugsun/mugsun-track) | Browser SDK; ingest handled by this repository’s track module |
| cloud / uniappx-ui | Planned or commercial intent; not open-source dependencies of this repository |

## License

[MIT](./LICENSE)