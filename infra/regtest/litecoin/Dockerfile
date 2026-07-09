FROM ubuntu:24.04

ARG LITECOIN_VERSION=0.21.4
ARG LITECOIN_SHA256=857fc41091f2bae65c3bf0fd4d388fca915fc93a03f16dd2578ac3cc92898390

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl socat \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fL --retry 3 \
      "https://download.litecoin.org/litecoin-${LITECOIN_VERSION}/linux/litecoin-${LITECOIN_VERSION}-x86_64-linux-gnu.tar.gz" \
      -o /tmp/litecoin.tar.gz \
    && echo "${LITECOIN_SHA256}  /tmp/litecoin.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/litecoin.tar.gz -C /tmp \
    && install -m 0755 "/tmp/litecoin-${LITECOIN_VERSION}/bin/litecoind" /usr/local/bin/litecoind \
    && install -m 0755 "/tmp/litecoin-${LITECOIN_VERSION}/bin/litecoin-cli" /usr/local/bin/litecoin-cli \
    && rm -rf /tmp/litecoin.tar.gz "/tmp/litecoin-${LITECOIN_VERSION}"

COPY entrypoint.sh /usr/local/bin/litecoin-regtest-entrypoint

VOLUME ["/var/lib/litecoin"]
EXPOSE 19445
ENTRYPOINT ["/usr/local/bin/litecoin-regtest-entrypoint"]
