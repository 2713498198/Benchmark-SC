FROM node:22-alpine
WORKDIR /app
COPY agent-proxy.mjs /app/agent-proxy.mjs
USER node
ENV PROXY_PORT=8000
ENV TARGET_HOST=agent
ENV TARGET_PORT=8000
EXPOSE 8000
CMD ["node", "/app/agent-proxy.mjs"]
