FROM ubuntu:24.04

ARG BITCOIN_VERSION=29.0
ARG BITCOIN_SHA256=a681e4f6ce524c338a105f214613605bac6c33d58c31dc5135bbc02bc458bb6c

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl socat \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fL --retry 3 \
      "https://bitcoincore.org/bin/bitcoin-core-${BITCOIN_VERSION}/bitcoin-${BITCOIN_VERSION}-x86_64-linux-gnu.tar.gz" \
      -o /tmp/bitcoin.tar.gz \
    && echo "${BITCOIN_SHA256}  /tmp/bitcoin.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/bitcoin.tar.gz -C /tmp \
    && install -m 0755 "/tmp/bitcoin-${BITCOIN_VERSION}/bin/bitcoind" /usr/local/bin/bitcoind \
    && install -m 0755 "/tmp/bitcoin-${BITCOIN_VERSION}/bin/bitcoin-cli" /usr/local/bin/bitcoin-cli \
    && rm -rf /tmp/bitcoin.tar.gz "/tmp/bitcoin-${BITCOIN_VERSION}"

COPY entrypoint.sh /usr/local/bin/bitcoin-regtest-entrypoint

VOLUME ["/var/lib/bitcoin"]
EXPOSE 18446
ENTRYPOINT ["/usr/local/bin/bitcoin-regtest-entrypoint"]
