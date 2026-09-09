# Two stages so the runtime image carries no npm cache, no source and no dev dependencies.
# Build for the deployment architecture explicitly when cross-building from x86 to the ARM box:
#   docker buildx build --platform linux/arm64 -f web/Dockerfile -t shush-web:latest web
FROM node:22.22.3-alpine AS build
WORKDIR /build

# Dependencies resolve in their own layer, so a source-only change does not reinstall them.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Baked in, not read at runtime. NEXT_PUBLIC_* is substituted into the client bundle at build
# time, so an empty value here is what makes the deployed app call its own origin -- which is
# the point: nginx serves this app and proxies /api to the API, so there is no second origin
# and no CORS in the normal path.
ARG NEXT_PUBLIC_API_BASE=""
ENV NEXT_PUBLIC_API_BASE=${NEXT_PUBLIC_API_BASE}
RUN npm run build

FROM node:22.22.3-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# Never run as root; nothing here needs it.
RUN addgroup -S shush && adduser -S -G shush shush

# `output: standalone` traces exactly the files the server needs and writes a server.js beside
# them. static/ and public/ are not traced because they are served, not imported.
COPY --from=build /build/.next/standalone ./
COPY --from=build /build/.next/static ./.next/static

USER shush
EXPOSE 3000

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=6 \
  CMD wget -qO- http://127.0.0.1:3000/ >/dev/null || exit 1

CMD ["node", "server.js"]
