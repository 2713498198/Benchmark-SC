FROM python:3.12.10-slim-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && python3 -m pip install --no-cache-dir python-sat==1.9.dev25 \
    && rm -rf /var/lib/apt/lists/*

COPY suite/ /opt/cryptobench/suite/

RUN useradd --create-home --uid 1000 judge
USER judge
WORKDIR /run
