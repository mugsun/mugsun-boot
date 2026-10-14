# 构建上下文：mugsun-boot 仓库根（复用 scripts/gen-keys.sh）
FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
RUN apk add --no-cache openssl
COPY scripts/gen-keys.sh /gen-keys.sh
COPY docker/full/gen-secrets.sh /gen-secrets.sh
RUN chmod +x /gen-keys.sh /gen-secrets.sh
ENTRYPOINT ["/gen-secrets.sh"]
