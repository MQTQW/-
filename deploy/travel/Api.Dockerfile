# syntax=docker/dockerfile:1.7
FROM node:24.22.2-bookworm-slim AS build
WORKDIR /app
COPY design/travel-live/ design/travel-live/
RUN node design/travel-live/build.mjs

FROM node:24.22.2-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/design/travel-live/index.html design/travel-live/index.html
COPY server/travel-live/ server/travel-live/
COPY server/travel-research/ server/travel-research/
COPY deploy/travel/admin-login.mjs deploy/travel/health.mjs deploy/travel/
RUN mkdir -p /var/lib/myplan && chown 1000:1000 /var/lib/myplan
USER 1000:1000
EXPOSE 8794
CMD ["node", "server/travel-live/serve.mjs"]

