![mugsun-boot](https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/banners/repos/mugsun-boot-en.png)

# mugsun-boot

[简体中文](./README.md) | English

This repository is the mugsun mid-office monolithic backend. Infrastructure is provided by Docker Compose; the application is served as an executable JAR. System administration and workflow live in the core module. GIS, analytics, and AI capabilities are assembled as optional modules.

[Documentation](https://docs.mugsun.com/en/boot/) · [Getting started](https://docs.mugsun.com/en/guide/getting-started) · [Security checklist](https://docs.mugsun.com/en/boot/security-checklist)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

## Overview

| Item | Detail |
| --- | --- |
| Coordinates | `com.mugsun:mugsun-boot:0.1.0` |
| Runtime | JDK 21; Maven 3.9+ |
| Framework | Spring Boot 3.5.13 |
| Data stores | PostgreSQL 16 (PostGIS + pgvector); Redis 7 (default database index `3`) |
| Dependency | [mugsun-core](https://github.com/mugsun/mugsun-core) `com.mugsun:mugsun-*:0.1.0` (published to [Maven Central](https://repo1.maven.org/maven2/com/mugsun/)) |
| License | [Apache-2.0](./LICENSE) |
| Docs | [docs.mugsun.com/en/boot](https://docs.mugsun.com/en/boot/) |

Stack (per current POM): MyBatis-Flex, Sa-Token, JetCache, Warm-Flow, Flyway, Spring AI 1.1.2 (AI module).

## Deployment

Prerequisites: JDK 21, Maven 3.9+, Docker. `mugsun-core` 0.1.0 is on Maven Central and is resolved automatically; a sibling clone is not required.

```bash
docker compose up -d
cp config/application-local.yml.example config/application-local.yml
mvn -pl mugsun-boot-server -am package -DskipTests
java -jar mugsun-boot-server/target/mugsun-boot.jar
```

Run `java -jar` from the repository root, otherwise `config/application-local.yml` is not picked up. For development you can optionally run `mvn -pl mugsun-boot-server -am spring-boot:run` from the repository root instead.

**Local install (offline or when Central has not synced):** clone [mugsun-core](https://github.com/mugsun/mugsun-core) as a sibling, run `cd ../mugsun-core && mvn clean install -DskipTests`, then return to this repository to build.

Health check: http://localhost:8080/ping

Default initial account: `admin` / `123456` (override with `MUGSUN_INIT_PASSWORD`). Production deployments must change the password. The `prod` profile refuses to start without an injected initial password.

Admin console: [mugsun-pc](https://github.com/mugsun/mugsun-pc) (development default port `3006`, proxied to this service on `8080`). Walkthrough: [Documentation · Getting started](https://docs.mugsun.com/en/guide/getting-started).

Package artifact: `mugsun-boot-server/target/mugsun-boot.jar`.

Compose ports: `5432` (PostgreSQL), `6379` (Redis). Development database credentials default to the JDBC settings (`mugsun` / `mugsun`). Without Docker, see `scripts/init-db.sql`.

Local configuration path: `optional:file:./config/application-local.yml` (gitignored). The working directory must be the repository root. Production injection: [Configuration](https://docs.mugsun.com/en/boot/configuration).

OpenAPI: http://localhost:8080/swagger-ui/index.html (disabled under `prod`).

## Human verification (slider captcha)

Login, registration, the forgot-password code and the SMS code request require a slider puzzle by default (built in, no third-party dependency). Flow: `GET /auth/captcha/config` (hint) → `POST /auth/captcha/challenge` → `POST /auth/captcha/verify` returns a one-time `captchaToken` → send `captchaToken` in the business request. The server re-checks tenant and client itself; `config` is only a hint. Error codes (HTTP 200 + `R.code`): `40010` expired, `40011` failed, `40012` rate limited (`data.retryAfter` seconds), `40013` token required, `40014` not required.

| Key | Default | Notes |
|---|---|---|
| `mugsun.captcha.enabled` | `true` | Global switch (set to true in prod config) |
| `mugsun.captcha.type` | `SLIDER` | `SLIDER` or `IMAGE` (legacy digits, kept for one release); server-side only |
| `mugsun.captcha.store` | `redis` | `redis` or `memory`; `memory` is single-node dev only and fails to start under the `prod` profile |
| `mugsun.captcha.slider.width` / `height` | `320` / `180` | Native background size |
| `mugsun.captcha.slider.tolerance-px` | `6` | Tolerance for drop position and track travel (native px) |
| `mugsun.captcha.slider.expire-seconds` / `token-expire-seconds` | `120` / `120` | Challenge and token lifetime |
| `mugsun.captcha.slider.min-duration-ms` | `300` | Server-timed minimum (issue → verify) |
| `mugsun.captcha.slider.decoy-count` | `1` | Fake gaps (0–2) |
| `mugsun.captcha.slider.bind-ip` / `bind-ua` | `false` | Enforce token binding to IP / UA hash (recorded either way) |
| `mugsun.captcha.background.dir` | `classpath:captcha/bg/` | Background folder (`file:` works too), jpg/png, 640×360 recommended; plain-colour placeholder when empty |
| `mugsun.captcha.background.rotate` | `random` | `random` or `round-robin` |
| `mugsun.captcha.background.reload-seconds` | `600` | Rescan interval, 0 = load at startup only |
| `mugsun.captcha.rate-limit.*` | see `application.yml` | Per-IP challenge rate, global token bucket, failure lockout (stricter for keyboard mode) |

Tenant switch: `sys_tenant.captcha_enabled` (`V4__tenant_captcha.sql`, default 1); only the platform super admin can change it via `POST /system/tenant/captcha`, and every change is audited. The client switch `sys_client.captcha_enabled` still applies to login only. The app endpoints `/app/captcha/*` keep their shape and share the same engine.

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

[Apache-2.0](./LICENSE)