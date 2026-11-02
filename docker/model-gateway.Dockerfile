FROM node:22-alpine
WORKDIR /app
COPY model-gateway.mjs /app/model-gateway.mjs
USER node
EXPOSE 8010 8011
CMD ["node", "/app/model-gateway.mjs"]
