FROM ubuntu:24.04

ARG BCHN_VERSION=29.0.0
ARG BCHN_SHA256=6125d1cbecc1db476f2b6b7b91da5acde92d2311b8e738124e3db64ca84b33e1

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl socat \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fL --retry 3 \
      "https://github.com/bitcoin-cash-node/bitcoin-cash-node/releases/download/v${BCHN_VERSION}/bitcoin-cash-node-${BCHN_VERSION}-x86_64-linux-gnu.tar.gz" \
      -o /tmp/bchn.tar.gz \
    && echo "${BCHN_SHA256}  /tmp/bchn.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/bchn.tar.gz -C /tmp \
    && install -m 0755 "/tmp/bitcoin-cash-node-${BCHN_VERSION}/bin/bitcoind" /usr/local/bin/bitcoind \
    && install -m 0755 "/tmp/bitcoin-cash-node-${BCHN_VERSION}/bin/bitcoin-cli" /usr/local/bin/bitcoin-cli \
    && ln -s /usr/local/bin/bitcoind /usr/local/bin/bitcoincashd \
    && rm -rf /tmp/bchn.tar.gz "/tmp/bitcoin-cash-node-${BCHN_VERSION}"

COPY entrypoint.sh /usr/local/bin/bitcoincash-regtest-entrypoint

VOLUME ["/var/lib/bitcoincash"]
EXPOSE 18445
ENTRYPOINT ["/usr/local/bin/bitcoincash-regtest-entrypoint"]
