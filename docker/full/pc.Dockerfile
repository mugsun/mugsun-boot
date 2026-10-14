# syntax=docker/dockerfile:1
# 管理台镜像：源码来自 additional_contexts（pc / track，默认 GitHub main），产物由 Nginx 托管并反代 /api。
# 构建方式与 mugsun-pc#18 的 Dockerfile 一致（Node 22 + pnpm 11、VITE_API_URL=/ 同源、对外端口 8088）。
FROM node:22-bookworm-slim AS build
ENV PNPM_HOME=/pnpm PATH=/pnpm:$PATH CI=true
RUN corepack enable
WORKDIR /src
COPY --from=track . mugsun-track/
COPY --from=pc . mugsun-pc/
RUN --mount=type=cache,target=/pnpm/store \
    cd mugsun-track && pnpm install --frozen-lockfile=false && pnpm build
ARG NODE_MAX_OLD_SPACE=6144
RUN --mount=type=cache,target=/pnpm/store \
    cd mugsun-pc && pnpm install --frozen-lockfile=false \
    && NODE_OPTIONS=--max-old-space-size=$NODE_MAX_OLD_SPACE pnpm exec vite build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/mugsun-pc/dist /usr/share/nginx/html
EXPOSE 80
