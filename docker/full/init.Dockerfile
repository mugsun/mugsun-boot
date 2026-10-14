# 构建上下文：mugsun-boot 仓库根（复用 scripts/gen-keys.sh）
FROM alpine:3.20
RUN apk add --no-cache openssl
COPY scripts/gen-keys.sh /gen-keys.sh
COPY docker/full/gen-secrets.sh /gen-secrets.sh
RUN chmod +x /gen-keys.sh /gen-secrets.sh
ENTRYPOINT ["/gen-secrets.sh"]
