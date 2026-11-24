# 화면과 프록시 이미지 (docs/DEPLOY.md). 화면을 빌드해 Caddy 가 내준다. 저장소 루트에서 빌드한다.
FROM node:22 AS build
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM caddy:2.8
COPY --from=build /web/dist /srv
COPY deploy/prod/Caddyfile /etc/caddy/Caddyfile
