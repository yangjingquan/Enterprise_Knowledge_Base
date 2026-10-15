FROM node:20-alpine AS build
WORKDIR /src
COPY package*.json ./
# BuildKit 缓存挂载：npm 下载缓存在多次构建之间复用
RUN --mount=type=cache,target=/root/.npm npm config set registry https://registry.npmmirror.com && npm ci --prefer-offline --no-audit --no-fund
COPY . .
# 限制 Node 堆内存，避免小内存服务器构建时大量换页
RUN NODE_OPTIONS=--max-old-space-size=768 npm run build
FROM nginx:alpine
COPY --from=build /src/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
