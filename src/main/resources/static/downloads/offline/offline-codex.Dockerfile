ARG CRYPTOBENCH_AGENT_BASE=cryptobench-agent-runtime:4.2.0
FROM ${CRYPTOBENCH_AGENT_BASE}

USER root
RUN npm install --global @openai/codex@0.167.2 \
    && npm cache clean --force
USER agent
