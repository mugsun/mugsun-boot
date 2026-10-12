<p align="center">
  <img src="https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/banners/github-banner-dark.png" alt="mugsun" width="100%" />
</p>

# mugsun-boot

[简体中文](./README.md) | English

**A privatizable mid-office backend: compose for data stores, one jar for the API.**  
RBAC and workflow as the base; GIS / analytics / AI as optional modules — not a low-code feature wall.

[Docs](https://docs.mugsun.com/en/boot/) · [Getting started](https://docs.mugsun.com/en/guide/getting-started) · [Demo (coming soon)](https://docs.mugsun.com/en/demo)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

---

## Why this

1. **You keep the data** — Postgres + Redis + this jar on your machines.
2. **You can slim it** — default `full` profile; `-Pbasic` or env flags drop GIS / track / AI.
3. **Analytics is a vertical slice** with [mugsun-track](https://github.com/mugsun/mugsun-track) and [mugsun-pc](https://github.com/mugsun/mugsun-pc), not a separate SaaS bill.

Not an open-sourced microservice distribution yet (`cloud` is planned). Not a hosted SaaS.

---

## Shortest local path

Needs JDK 21, Maven 3.9+, Docker; clone [mugsun-core](https://github.com/mugsun/mugsun-core) as a sibling.

```bash
cd ../mugsun-core && mvn clean install -DskipTests && cd ../mugsun-boot
docker compose up -d
cp config/application-local.yml.example config/application-local.yml
mvn -pl mugsun-boot-server -am spring-boot:run
```

Probe http://localhost:8080/ping. Default login **`admin` / `123456`** (`MUGSUN_INIT_PASSWORD` overrides). **Change it for production** — `prod` refuses to boot without an injected init password.

Admin UI: [mugsun-pc](https://github.com/mugsun/mugsun-pc). Full walkthrough: [docs](https://docs.mugsun.com/en/guide/getting-started).

---

## Shape

```mermaid
flowchart TB
  PC["mugsun-pc :3006"] -->|HTTP / WS| BOOT["mugsun-boot :8080"]
  TRACK["@mugsun/track-web"] -->|/track/collect| BOOT
  BOOT --> PG[("PostgreSQL")]
  BOOT --> RD[("Redis")]
```

> **Screenshot placeholder** — Swagger / admin shots go here when ready. See [Demo](https://docs.mugsun.com/en/demo).

Module profiles: [docs/可选模块装配.md](docs/可选模块装配.md) (Chinese). This README does not duplicate a feature wall.

---

## Honest boundaries

Free OSS line: `boot` · `core` · `pc` · `track`. No public demo yet; no open `cloud` release; uniappx-ui is not a free dependency.

## License

[Apache-2.0](./LICENSE)
