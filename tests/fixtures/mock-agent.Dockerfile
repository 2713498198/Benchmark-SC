FROM node:22-alpine
WORKDIR /app
COPY mock-agent.mjs /app/mock-agent.mjs
EXPOSE 8000
USER node
ENV MOCK_AGENT_HOST=0.0.0.0
CMD ["node", "/app/mock-agent.mjs", "--port", "8000"]
