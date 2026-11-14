FROM node:20-bookworm-slim
WORKDIR /app
ARG NEXT_PUBLIC_API_URL=/api/v1
ARG API_INTERNAL_URL=http://api:3001
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}
ENV API_INTERNAL_URL=${API_INTERNAL_URL}
COPY . .
RUN npm install && npm run build -w @anb/dashboard
EXPOSE 3000
CMD ["npm", "run", "start", "-w", "@anb/dashboard"]
