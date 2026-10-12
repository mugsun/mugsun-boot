# mugsun-boot

[简体中文](./README.md) | English

A mid-office backend service intended for private deployment. It provides system administration and workflow foundations; geographic information, behavioral analytics, and AI capabilities are available as optional modules.

[Documentation](https://docs.mugsun.com/en/boot/) · [Getting started](https://docs.mugsun.com/en/guide/getting-started) · [Security checklist](https://docs.mugsun.com/en/boot/security-checklist)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

## Requirements and startup

Requires JDK 21, Maven 3.9 or later, Docker, and a sibling clone of [mugsun-core](https://github.com/mugsun/mugsun-core). core is not published to Maven Central; install it locally before building this repository.

```bash
cd ../mugsun-core && mvn clean install -DskipTests && cd ../mugsun-boot
docker compose up -d
cp config/application-local.yml.example config/application-local.yml
mvn -pl mugsun-boot-server -am spring-boot:run
```

The service listens on http://localhost:8080 by default. Health check: `GET /ping`. Initial administrator username is `admin`; initial password is `123456`, overridable via `MUGSUN_INIT_PASSWORD`. The password must be changed for production use. Under the `prod` profile, the process refuses to start if no initial password is injected. Admin UI: [mugsun-pc](https://github.com/mugsun/mugsun-pc). Procedures: [Getting started](https://docs.mugsun.com/en/guide/getting-started). A public demonstration environment is not available.

## Overview

This repository delivers a monolithic application backed by PostgreSQL and Redis. Authentication, authorization, multi-tenancy, workflow, and the open platform reside in the core module. Geographic information, behavioral analytics, and AI are optional and may be reduced via Maven profiles or environment variables.

The free open-source line comprises boot, core, pc, and track. The microservice edition (`cloud`) and uniappx-ui remain in planning or commercial arrangement and are not published open-source dependencies of this repository.

Technology baseline (current POM): JDK 21, Spring Boot 3.5.13, this repository and core at 0.1.0, MyBatis-Flex, Sa-Token, JetCache, Warm-Flow, Flyway. PostgreSQL 16 is preferred (compose image includes PostGIS and pgvector). Redis defaults to logical database 3.

## Architecture

<p align="center">
  <img src="https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/arch/boot-iso-en.jpg" alt="Architecture" width="100%" />
</p>

```text
mugsun-boot/
  mugsun-boot-core/
  mugsun-boot-gis/       # optional
  mugsun-boot-track/     # optional
  mugsun-boot-ai/        # optional
  mugsun-boot-server/    # default profile: full
```

Default profile: `full`. Core-only: `-Pbasic,'!full'`. Additional profiles and `MUGSUN_*_ENABLED` flags are described in [可选模块装配.md](docs/可选模块装配.md) (Chinese).

## Modules

The core module covers users and permissions, data-scope controls, audit, messaging, files, code generation and online forms, Warm-Flow, and the open platform (OAuth2 authorization code with PKCE, API keys). Transport and audit paths include SM-related capabilities; verify against the [security checklist](https://docs.mugsun.com/en/boot/security-checklist) before production.

The track module provides `/track/collect` and analytics APIs, forming an end-to-end path with [mugsun-track](https://github.com/mugsun/mugsun-track) and mugsun-pc. GIS and AI modules are within the supported surface only when enabled.

The PowerJob worker is disabled by default. OpenAPI: http://localhost:8080/swagger-ui/index.html (disabled under `prod`).

## Related repositories

| Repository | Description |
| --- | --- |
| [mugsun-core](https://github.com/mugsun/mugsun-core) | BOM and starters |
| [mugsun-pc](https://github.com/mugsun/mugsun-pc) | Administration UI |
| [mugsun-track](https://github.com/mugsun/mugsun-track) | Browser SDK |
| [mugsun-docs](https://github.com/mugsun/mugsun-docs) | Documentation site |

## Local build notes

Build and run from the repository root so `config/application-local.yml` can be loaded.

```bash
mvn -pl mugsun-boot-server -am package -DskipTests
java -jar mugsun-boot-server/target/mugsun-boot.jar
```

Compose maps PostgreSQL `5432` and Redis `6379` by default. Initial credentials are for local verification only. Configuration reference: [Documentation](https://docs.mugsun.com/en/boot/configuration).

## License

[Apache-2.0](./LICENSE)
