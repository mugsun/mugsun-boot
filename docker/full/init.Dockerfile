FROM alpine:3.20
RUN apk add --no-cache openssl
COPY gen-secrets.sh /gen-secrets.sh
ENTRYPOINT ["/gen-secrets.sh"]
