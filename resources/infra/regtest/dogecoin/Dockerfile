FROM ubuntu:24.04

ARG DOGECOIN_VERSION=1.14.9
ARG DOGECOIN_SHA256=4f227117b411a7c98622c970986e27bcfc3f547a72bef65e7d9e82989175d4f8

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl socat \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fL --retry 3 \
      "https://github.com/dogecoin/dogecoin/releases/download/v${DOGECOIN_VERSION}/dogecoin-${DOGECOIN_VERSION}-x86_64-linux-gnu.tar.gz" \
      -o /tmp/dogecoin.tar.gz \
    && echo "${DOGECOIN_SHA256}  /tmp/dogecoin.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/dogecoin.tar.gz -C /tmp \
    && install -m 0755 "/tmp/dogecoin-${DOGECOIN_VERSION}/bin/dogecoind" /usr/local/bin/dogecoind \
    && install -m 0755 "/tmp/dogecoin-${DOGECOIN_VERSION}/bin/dogecoin-cli" /usr/local/bin/dogecoin-cli \
    && rm -rf /tmp/dogecoin*

COPY entrypoint.sh /usr/local/bin/dogecoin-regtest-entrypoint

VOLUME ["/var/lib/dogecoin"]
EXPOSE 22556
ENTRYPOINT ["/usr/local/bin/dogecoin-regtest-entrypoint"]
