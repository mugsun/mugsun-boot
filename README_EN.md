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

Stack (per current POM): MyBatis-Flex, Sa-Token, JetCache, Warm-Flow, Flyway. The AI module does not depend on Spring AI: model calls, vector retrieval and MCP tool calls are implemented with the module's own HTTP client and pgvector queries.

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

> **Always build from the repository root with `-am`** (or build the whole project with `mvn clean package -DskipTests`). If you build only the server module (`-pl mugsun-boot-server` without `-am`, `mvn package` inside `mugsun-boot-server`, or an IDE building just that module), `mugsun-boot-core` comes from the local Maven repository at `~/.m2/repository/com/mugsun/mugsun-boot-core/0.1.0/`. Every branch and worktree shares that one 0.1.0 version, which holds whichever branch ran `mvn install` last, so the jar can carry another branch's old code (such as an old captcha response shape) whether or not you add `clean`. When in doubt, run `mvn -B install -DskipTests` from the root first.

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

## Human verification (behavioural captcha)

Login, registration, the forgot-password code and the SMS code request require a behavioural captcha by default (built in, no third-party dependency; the platform default types are the slider puzzle and word click, and the server picks one at random). Flow: `GET /auth/captcha/config` (hint) → `POST /auth/captcha/challenge` → `POST /auth/captcha/verify` returns a one-time `captchaToken` → send `captchaToken` in the business request. The server re-checks tenant and client itself; `config` is only a hint. Error codes (HTTP 200 + `R.code`): `40010` expired, `40011` failed, `40012` rate limited (`data.retryAfter` seconds), `40013` token required, `40014` not required.

| Key | Default | Notes |
|---|---|---|
| `mugsun.captcha.enabled` | `true` | Global switch; `false` turns verification off for every scene (set to true in prod config) |
| `mugsun.captcha.default-types` | `SLIDER,WORD_CLICK,ROTATE,ICON_CLICK,JIGSAW` | Types enabled when a tenant has no own setting: `SLIDER`, `ROTATE`, `WORD_CLICK`, `ICON_CLICK`, `JIGSAW` (case-insensitive). All five are on by default and one is picked at random per challenge. Invalid values fail startup |
| `mugsun.captcha.type` | unset | Deprecated: only `SLIDER` is accepted (no effect, WARN at startup). The legacy digit captcha `IMAGE` was removed in v0.6.3; `IMAGE` or any other value fails startup |
| `mugsun.captcha.store` | `redis` | `redis` or `memory`; `memory` is single-node dev only and fails to start under the `prod` profile |
| `mugsun.captcha.slider.width` / `height` | `320` / `180` | Native background size |
| `mugsun.captcha.slider.tolerance-px` | `6` | Tolerance for drop position and track travel (native px) |
| `mugsun.captcha.slider.expire-seconds` / `token-expire-seconds` | `120` / `120` | Challenge and token lifetime |
| `mugsun.captcha.slider.min-duration-ms` | `300` | Server-timed minimum (issue → verify) |
| `mugsun.captcha.slider.decoy-count` | `1` | Fake gaps (0–2) |
| `mugsun.captcha.slider.keyboard-step-px` | `4` | Native pixels moved per key press in keyboard mode (returned as `keyboardStep`) |
| `mugsun.captcha.slider.bind-ip` / `bind-ua` | `false` / `false` (`bind-ua` is `true` in prod) | Enforce token binding to IP / UA hash (recorded either way) |
| `mugsun.captcha.background.dir` | `classpath:captcha/bg/` | Background folder (`file:` works too), jpg/png, 640×360 recommended; plain-colour placeholder when empty |
| `mugsun.captcha.background.rotate` | `random` | `random` or `round-robin` |
| `mugsun.captcha.background.reload-seconds` | `600` | Rescan interval, 0 = load at startup only |
| `mugsun.captcha.rate-limit.*` | see `application.yml` | Per-IP challenge rate, global per-second cap (shared across nodes via Redis), failure lockout (stricter for keyboard mode) |
| `mugsun.captcha.rate-limit.sms-phone-per-day` / `sms-ip-per-hour` | `10` / `20` | Limits on sending SMS codes (on top of the 60-second per-number throttle), independent of the captcha switch |

Tenant switch: `sys_tenant.captcha_enabled` (`V9__tenant_captcha.sql`, default 1, always on for new tenants); only the platform super admin can change it via `POST /system/tenant/captcha`, and every change is audited. Other nodes pick it up within 60 seconds. Sending SMS codes does not depend on the tenant; only the global switch applies. The client switch `sys_client.captcha_enabled` is not consulted (the caller picks the clientId). The input mode (`inputMode`) is declared when fetching the challenge and must match at verify. The app endpoints `/app/captcha/*` keep their shape and share the same engine.

## Composition

<p align="center">
  <img src="https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/arch/boot-iso-en.jpg" alt="Architecture diagram" width="100%" />
</p>

```text
mugsun-boot/
  mugsun-boot-core/     # auth, system, workflow, tenant, open platform, …
  mugsun-boot-gis/      # optional: layers, spatial queries, vector tiles
  mugsun-boot-track/    # optional: ingest, aggregation, replay-related APIs
  mugsun-boot-ai/       # optional: models, chat, knowledge base, calling external MCP tools
  mugsun-boot-server/   # assembly shell; default profile = full
```

Maven profiles:

| Profile | Description |
| --- | --- |
| `full` (default) | Assembles core + gis + track + ai |
| `basic` | Core only; disable full: `-Pbasic,'!full'` |
| `gis-only` / `track-only` / `ai-only` | Core + one optional module; disable full |
| `kingbase` / `dameng` | Domestic database drivers |

Runtime flags (classes may still be on the classpath). Unset means on. When set, only `true` or `false` is accepted (case-insensitive, no surrounding spaces); an empty value, `1` / `0`, `yes` / `no` or anything else stops startup:

- `MUGSUN_GIS_ENABLED=false`
- `MUGSUN_TRACK_ENABLED=false`
- `MUGSUN_AI_ENABLED=false`

The built-in MCP Server is temporarily offline and will return with authentication. While it is offline, `/sse` and `/mcp` return 404 and `MUGSUN_AI_MCP_SERVER` has no effect; a maven-enforcer rule in the root POM temporarily blocks Spring AI (`org.springframework.ai:*`) and the MCP SDK (`io.modelcontextprotocol*`), including transitive dependencies.

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
- `mugsun-boot-ai`: models, chat, knowledge base, and calling external MCP tools from flows (the built-in MCP Server is temporarily offline and will return with authentication)

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