# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM node:20-alpine AS build

WORKDIR /app

# Copy package files first so Docker can cache the npm install layer.
# If only source files change, this layer is reused — much faster rebuilds.
COPY package.json package-lock.json ./
RUN npm ci

# Copy source and build
COPY . .
RUN npm run build
# Output lands in /app/dist

# ── Stage 2: Serve ────────────────────────────────────────────────────────────
FROM nginx:alpine

# Copy the built assets into the Nginx default serve directory
COPY --from=build /app/dist /usr/share/nginx/html

# Replace the default Nginx config with ours.
# This is what enables the /api and /ws reverse proxy rules.
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
