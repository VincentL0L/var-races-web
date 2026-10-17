# The VAR Races server (crates/server): a static Rust binary in an otherwise empty image.
# Render (and most hosts) give the port in $PORT; FIREBASE_SERVICE_ACCOUNT turns on ranked.
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
# TLS roots for Google's sign-in keys and Firestore are built into the binary (webpki-roots)
ENV PORT=8080
EXPOSE 8080
USER 65534:65534
ENTRYPOINT ["/var-races-server"]
