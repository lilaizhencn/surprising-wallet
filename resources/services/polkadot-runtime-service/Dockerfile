FROM node:20-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev --package-lock=false

COPY src ./src

ENV NODE_ENV=production \
    POLKADOT_RUNTIME_HOST=0.0.0.0 \
    POLKADOT_RUNTIME_PORT=8787

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:8787/health').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"

CMD ["npm", "start"]
