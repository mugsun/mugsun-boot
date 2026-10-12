# mugsun-boot

[简体中文](./README.md) | English

A privatizable mid-office backend: compose for the data stores, one jar for the API.  
RBAC and workflow as the base; GIS, analytics, and AI as optional modules—data stays on your machines.

[Docs](https://docs.mugsun.com/en/boot/) · [Getting started](https://docs.mugsun.com/en/guide/getting-started) · [Security checklist](https://docs.mugsun.com/en/boot/security-checklist)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

Needs JDK 21, Maven 3.9+, Docker, and a sibling clone of [mugsun-core](https://github.com/mugsun/mugsun-core) (`mvn install` first—core is not on Maven Central yet).

```bash
cd ../mugsun-core && mvn clean install -DskipTests && cd ../mugsun-boot
docker compose up -d
cp config/application-local.yml.example config/application-local.yml
mvn -pl mugsun-boot-server -am spring-boot:run
```

Probe http://localhost:8080/ping. Default **`admin` / `123456`** (`MUGSUN_INIT_PASSWORD` overrides). Change it for production—`prod` refuses to start without an injected init password. Admin UI: [mugsun-pc](https://github.com/mugsun/mugsun-pc). Walkthrough: [getting started](https://docs.mugsun.com/en/guide/getting-started). There is no public demo yet; local compose is the path.

## What it solves

boot is a take-home monolith: Postgres + Redis + this executable. Auth, permissions, tenancy, workflow, and the open platform live in core; GIS, product analytics, and AI are optional modules you can drop with Maven profiles or env flags. The free OSS line is `boot` · `core` · `pc` · `track`. Microservice `cloud` and uniappx-ui are planned or commercial—do not treat them as open dependencies of this repo.

Stack from the current POM: JDK 21, Spring Boot **3.5.13**, this repo / core **0.1.0**, MyBatis-Flex, Sa-Token, JetCache, Warm-Flow, Flyway. PostgreSQL 16 (PostGIS + pgvector in compose) and Redis db `3` by default.

## Architecture

<p align="center">
  <img src="https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/arch/boot-iso-en.jpg" alt="Architecture" width="100%" />
</p>

```text
mugsun-boot/
  mugsun-boot-core/
  mugsun-boot-gis/      # optional
  mugsun-boot-track/    # optional
  mugsun-boot-ai/       # optional
  mugsun-boot-server/   # default profile = full
```

Default `full`; `-Pbasic,'!full'` for core only; see [可选模块装配.md](docs/可选模块装配.md) (Chinese) for profiles and `MUGSUN_*_ENABLED` flags.

## Modules

core covers users/roles/menus, data permissions, audit, messaging, files, codegen/forms, Warm-Flow, and the open platform (OAuth2 + PKCE, API keys). Crypto/audit includes SM-related paths—check the [security checklist](https://docs.mugsun.com/en/boot/security-checklist) before production. The track module owns `/track/collect` and analytics APIs, pairing with the [track SDK](https://github.com/mugsun/mugsun-track) and pc dashboards. GIS and AI count only when enabled.

PowerJob worker is off by default. OpenAPI: http://localhost:8080/swagger-ui/index.html (disabled in `prod`).

## Related repos

| Repo | Role |
| --- | --- |
| [mugsun-core](https://github.com/mugsun/mugsun-core) | BOM / starters |
| [mugsun-pc](https://github.com/mugsun/mugsun-pc) | Admin UI |
| [mugsun-track](https://github.com/mugsun/mugsun-track) | Browser SDK |
| [mugsun-docs](https://github.com/mugsun/mugsun-docs) | docs.mugsun.com |

## Local development

Run Maven from the repo root so `config/application-local.yml` loads. Package with `mvn -pl mugsun-boot-server -am package -DskipTests`. Compose exposes `5432` / `6379`. Demo passwords are for local use only. More config: [docs](https://docs.mugsun.com/en/boot/configuration).

## License

[Apache-2.0](./LICENSE)
