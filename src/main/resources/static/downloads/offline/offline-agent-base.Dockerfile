# Standard offline agent runtime. Pin this image by version or digest in every run profile.
FROM node:22.14.0-bookworm-slim

LABEL org.cryptobench.runtime="cryptobench-agent-runtime" \
      org.cryptobench.runtime.version="4.2.0" \
      org.cryptobench.node.version="22.14.0" \
      org.cryptobench.python.version="3.11"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       bash build-essential ca-certificates curl git jq passwd python3 python3-pip \
    && python3 -m pip install --no-cache-dir --break-system-packages \
       pycryptodome==3.23.0 \
       python-sat==1.9.dev25 \
       sympy==1.13.3 \
       z3-solver==4.15.3.0 \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 1000 agent \
    && mkdir -p /workspace \
    && chown -R agent:agent /workspace

WORKDIR /workspace
USER agent

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    NODE_ENV=production
