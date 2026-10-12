# mugsun-boot

**中文** | [English](./README_EN.md)

可私有化的 Spring Boot 中后台：一条 compose 起库，一个 jar 对外。  
系统管理与工作流打底；GIS、埋点、AI 按需装配——不是功能墙，也不绑云。

[文档](https://docs.mugsun.com/boot/) · [快速开始](https://docs.mugsun.com/guide/getting-started)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

前置：JDK 21、Maven 3.9+、Docker；与 [mugsun-core](https://github.com/mugsun/mugsun-core) **平级** clone。

```bash
cd ../mugsun-core && mvn clean install -DskipTests && cd ../mugsun-boot
docker compose up -d
cp config/application-local.yml.example config/application-local.yml
mvn -pl mugsun-boot-server -am spring-boot:run
```

打开 http://localhost:8080/ping 。默认 `admin` / `123456`（`MUGSUN_INIT_PASSWORD` 可覆盖）。**生产必须改密**；`prod` 未注入初始口令会拒绝启动。

管理台：[mugsun-pc](https://github.com/mugsun/mugsun-pc)（`:3006` → `:8080`）。浏览器埋点用 [track SDK](https://github.com/mugsun/mugsun-track)，本仓负责摄入，pc 出看板。

数据与密钥留在你机器上。默认全量模块；`-Pbasic` 或环境变量可裁掉可选件。cloud 仍在规划。开源主线：`boot` · `core` · `pc` · `track`。模块开关见 [docs/可选模块装配.md](docs/可选模块装配.md)；其余以 [docs.mugsun.com](https://docs.mugsun.com/) 与源码为准。

[Apache-2.0](./LICENSE)
