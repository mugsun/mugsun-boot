# 构建上下文：mugsun-boot 仓库根（复用 scripts/gen-keys.sh）
FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
# psql + bcrypt：init show 判断管理员是否已改过口令（改过就不再显示初始口令）
RUN apk add --no-cache openssl postgresql16-client python3 py3-bcrypt
COPY scripts/gen-keys.sh /gen-keys.sh
COPY docker/full/gen-secrets.sh /gen-secrets.sh
COPY docker/full/init-pw-check.py /init-pw-check.py
RUN chmod +x /gen-keys.sh /gen-secrets.sh
ENTRYPOINT ["/gen-secrets.sh"]
