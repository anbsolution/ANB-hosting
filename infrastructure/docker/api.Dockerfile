FROM node:20-bookworm-slim
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends openssl \
    && rm -rf /var/lib/apt/lists/*
COPY . .
RUN npm install \
    && npm run db:generate \
    && npm run build -w @anb/api
EXPOSE 3001
CMD ["npm", "run", "start:prod", "-w", "@anb/api"]
