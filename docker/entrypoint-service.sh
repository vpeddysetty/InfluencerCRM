#!/bin/sh
# Materialize TLS key material from the environment, then start the service.
#
# WHY THIS EXISTS
#
# Fargate's native secret injection sets ENVIRONMENT VARIABLES from Secrets Manager. Spring's
# server.ssl.key-store wants a FILE. Those two facts do not meet without a step in between, and
# this is that step: it decodes a base64 secret into a file on an in-memory filesystem and then
# execs the JVM.
#
# The alternatives were worse:
#   - Bake the .p12 into the image. Then the private key is in every registry layer and anyone who
#     can pull the image has it. This is exactly what docs/keystore-rotation.md is an incident
#     report about.
#   - A sidecar that writes the file to a shared volume. More moving parts, and the app must then
#     wait for the sidecar to finish before it can bind its port.
#
# WHY /dev/shm
#
# tmpfs, so the decoded key never touches a disk and never survives the task. Writing it under
# /app or /tmp on the container's writable layer would leave the private key readable to anything
# that later gets a shell in the container, and would block `readonlyRootFilesystem` in the task
# definition. /dev/shm is writable even with a read-only root.
#
# NO-OP BY DEFAULT
#
# With no DAO_KEYSTORE_B64 set, this script does nothing but exec — so local `docker run` and any
# service that terminates TLS at the ALB use the identical image and the identical entrypoint. The
# behaviour is opt-in by the presence of a secret, not by a separate image or a build flag.
set -eu

SECURE_DIR=/dev/shm/influencrm

# --- Server keystore (the DAO's own TLS identity) -------------------------------------------
# base64 because Secrets Manager binary secrets arrive base64-encoded through the `secrets` block,
# and because a PKCS12 is binary: passing it raw through an env var would corrupt it.
if [ -n "${DAO_KEYSTORE_B64:-}" ]; then
    mkdir -p "$SECURE_DIR"
    # umask before the write, not chmod after: chmod leaves a window in which the file exists and
    # is world-readable.
    (umask 077 && echo "$DAO_KEYSTORE_B64" | base64 -d > "$SECURE_DIR/keystore.p12")

    # Fail loudly on a truncated or non-base64 secret. Without this check Spring reports a generic
    # "failed to load keystore" and the actual cause — a secret that was pasted with a newline, or
    # stored as text rather than binary — stays invisible.
    if [ ! -s "$SECURE_DIR/keystore.p12" ]; then
        echo "FATAL: DAO_KEYSTORE_B64 decoded to an empty file — check the secret is valid base64." >&2
        exit 1
    fi

    # Only set the path if the operator has not already pointed it somewhere themselves (e.g. at a
    # volume-mounted file), so an explicit DAO_KEYSTORE always wins over this convenience path.
    : "${DAO_KEYSTORE:=file:$SECURE_DIR/keystore.p12}"
    export DAO_KEYSTORE
    echo "TLS: server keystore materialized from DAO_KEYSTORE_B64"
fi

# --- Client truststore (used by the BFF to verify the DAO) ----------------------------------
# Normally unnecessary: the truststore holds only a public certificate and ships on the classpath.
# This path exists for the deployment where the DAO gets a CA-issued or rotated certificate and the
# committed truststore is out of date — rotating then means updating one secret, not rebuilding and
# redeploying the BFF image.
if [ -n "${WEBE_DAO_TRUST_STORE_B64:-}" ]; then
    mkdir -p "$SECURE_DIR"
    (umask 077 && echo "$WEBE_DAO_TRUST_STORE_B64" | base64 -d > "$SECURE_DIR/dao-truststore.p12")

    if [ ! -s "$SECURE_DIR/dao-truststore.p12" ]; then
        echo "FATAL: WEBE_DAO_TRUST_STORE_B64 decoded to an empty file — check the secret is valid base64." >&2
        exit 1
    fi

    : "${WEBE_DAO_TRUST_STORE:=file:$SECURE_DIR/dao-truststore.p12}"
    export WEBE_DAO_TRUST_STORE
    echo "TLS: DAO truststore materialized from WEBE_DAO_TRUST_STORE_B64"
fi

# `exec` so the JVM replaces this shell and becomes PID 1, receiving SIGTERM directly. Without it
# the shell holds PID 1, does not forward the signal, and Fargate waits out the full stop timeout
# (30s by default) before SIGKILLing — turning every deploy into a rolling 30-second stall and
# denying Spring the chance to drain in-flight requests.
# shellcheck disable=SC2086  # JAVA_OPTS is intentionally word-split into separate JVM arguments.
exec java $JAVA_OPTS -jar /app/app.jar
