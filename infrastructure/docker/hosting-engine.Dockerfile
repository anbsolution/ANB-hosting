FROM node:20-bookworm-slim
WORKDIR /app
COPY . .
RUN npm install -w @anb/hosting-engine \
    && npm run build -w @anb/hosting-engine
EXPOSE 3010
CMD ["npm", "run", "start", "-w", "@anb/hosting-engine"]
