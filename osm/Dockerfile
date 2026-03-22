FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    jq gzip \
    osmium-tool pyosmium \
    curl \
    ca-certificates \
    gcc \
    libc6-dev \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://go.dev/dl/go1.26.1.linux-amd64.tar.gz | tar -C /usr/local -xzf - \
    && ln -s /usr/local/go/bin/go /usr/local/bin/go

WORKDIR /app

COPY sqlite/ ./sqlite/
COPY *.json ./
COPY *.sh ./
COPY *.txt ./

RUN cd sqlite && CGO_ENABLED=1 go build -o osm-to-sqlite .

ENV CGO_ENABLED=1
ENV OSMIUM_POOL_THREADS=4
ENV PATH="/app:${PATH}"

ENTRYPOINT ["./extract.sh"]
