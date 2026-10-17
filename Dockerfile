# The VAR Races server (crates/server): a static Rust binary in an otherwise empty image.
# Render (and most hosts) give the port in $PORT.
FROM rust:1.97-alpine AS build
RUN apk add --no-cache musl-dev
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
COPY crates crates
COPY data data
RUN cargo build --release --locked -p server \
    && strip target/release/var-races-server

FROM scratch
COPY --from=build /app/target/release/var-races-server /var-races-server
ENV PORT=8080
EXPOSE 8080
USER 65534:65534
ENTRYPOINT ["/var-races-server"]
