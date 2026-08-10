#!/usr/bin/env bash
# Generates the LOCAL-ONLY key material the containerized stack needs to start, and writes the
# variables that reference it to docker/certs/.env.local.
#
#   ./docker/certs/generate-dev-secrets.sh
#   docker compose --env-file docker/certs/.env.local -f docker-compose.yml -f docker-compose.hostports.yml up -d
#
# WHY A SCRIPT AND NOT COMMITTED FILES. Every value here is a private key or a signing secret. A
# committed one is a compromised one — that is the whole subject of docs/keystore-rotation.md, where
# a keystore and its password were committed together. So these are generated per machine, written
# only to gitignored paths, and regenerable at any time.
#
# NONE OF THIS IS FOR A DEPLOYED ENVIRONMENT. In Fargate each value below is a Secrets Manager entry
# injected under the same variable name; see docs/infrastructure/containerization.md. The point of
# generating them here is that the container path is exercised locally with the same guards enabled,
# rather than a shortcut locally and an untested path in the cluster.
set -euo pipefail

cd "$(dirname "$0")"
OUT_ENV=".env.local"

# --- DAO TLS keystore + the BFF's matching truststore ----------------------------------------
# SANs cover every name the DAO is reachable by: `localhost` (a single Fargate task shares one
# network namespace, and host-run dev), `dao` (the compose service name) and `influencer-dao` (the
# name docs/keystore-rotation.md uses). The BFF verifies this certificate, so a name missing here
# fails the handshake — and the tempting fix at that point is to disable verification entirely.
if [ ! -f keystore.p12 ]; then
    echo "==> generating DAO keystore + truststore"
    # keytool via a container: no JDK is required on the host, and the keystore is then produced by
    # the same Java version that will read it.
    docker run --rm -v "$(pwd):/certs" eclipse-temurin:17-jdk sh -c '
        set -e
        keytool -genkeypair -alias influencerdao \
            -keyalg RSA -keysize 2048 -validity 825 \
            -storetype PKCS12 -keystore /certs/keystore.p12 -storepass changeit \
            -dname "CN=influencer-dao,OU=platform,O=influencrm,C=US" \
            -ext "SAN=dns:localhost,dns:dao,dns:influencer-dao"
        keytool -exportcert -alias influencerdao -keystore /certs/keystore.p12 \
            -storetype PKCS12 -storepass changeit -file /certs/dao-cert.crt
        keytool -importcert -noprompt -alias influencerdao -file /certs/dao-cert.crt \
            -keystore /certs/dao-truststore.p12 -storetype PKCS12 -storepass changeit
    '
    # The BFF loads the truststore from its classpath, so the generated one has to replace the
    # committed copy or it still trusts the OLD certificate and every DAO call fails verification.
    cp dao-truststore.p12 ../../InfluencerWebExperience/src/main/resources/dao-truststore.p12
    echo "    installed truststore into InfluencerWebExperience resources (rebuild that image)"
else
    echo "==> keystore.p12 exists, leaving it alone (delete it to rotate)"
fi

# --- BFF access-token signing key (RSA JWK, private) -----------------------------------------
# The BFF REFUSES TO START without this: SigningKeySet rejects an ephemeral key because tokens it
# signs cannot be verified after a restart or by a second instance, which presents as intermittent
# auth failures rather than a clear error.
if [ ! -f jwt-signing-key.json ]; then
    echo "==> generating BFF JWT signing key (RSA JWK)"
    # openssl generates the key; python converts it to the JWK form nimbus expects. Both are already
    # required elsewhere in this repo, so this adds no new tooling.
    openssl genrsa 2048 2>/dev/null > jwt-signing-key.pem
    python - <<'PY' > jwt-signing-key.json
import base64, json, re, subprocess
txt = subprocess.run(["openssl","rsa","-in","jwt-signing-key.pem","-text","-noout"],
                     capture_output=True, text=True).stdout
def grab(label):
    m = re.search(label + r":\s*\n((?:\s+[0-9a-f:]+\n)+)", txt)
    return bytes(int(b, 16) for b in m.group(1).replace("\n","").replace(" ","").strip(":").split(":") if b)
def b64u(b):
    return base64.urlsafe_b64encode(b.lstrip(b"\x00") or b"\x00").decode().rstrip("=")
e = int(re.search(r"publicExponent:\s*(\d+)", txt).group(1))
print(json.dumps({
    "kty":"RSA","use":"sig","alg":"RS256","kid":"influencrm-local-1",
    "n":  b64u(grab("modulus")),
    "e":  b64u(e.to_bytes((e.bit_length()+7)//8, "big")),
    "d":  b64u(grab("privateExponent")),
    "p":  b64u(grab("prime1")),    "q":  b64u(grab("prime2")),
    "dp": b64u(grab("exponent1")), "dq": b64u(grab("exponent2")),
    "qi": b64u(grab("coefficient")),
}, separators=(",",":")))
PY
    rm -f jwt-signing-key.pem
else
    echo "==> jwt-signing-key.json exists, leaving it alone (delete it to rotate)"
fi

# --- Shared secrets -------------------------------------------------------------------------
# Written once and then reused, so restarting the stack does not invalidate anything already issued.
new_secret() { openssl rand -base64 "${1:-32}" | tr -d '\n'; }

if [ ! -f .secrets.generated ]; then
    echo "==> generating shared service secrets"
    {
        echo "DAO_SERVICE_TOKEN=$(new_secret 32)"
        echo "WORKFLOW_SERVICE_TOKEN=$(new_secret 32)"
        echo "DPS_SERVICE_TOKEN=$(new_secret 32)"
        # Symmetric workload key. Shared rather than per-service for now: any holder can also mint,
        # which is why the asymmetric Ed25519 path is the documented next step.
        echo "WORKLOAD_SIGNING_KEY=$(new_secret 48)"
        echo "MARKETPLACE_CREDENTIAL_KEY=$(new_secret 32)"
    } > .secrets.generated
fi
# shellcheck disable=SC1091
. ./.secrets.generated

# --- Emit the compose env file ---------------------------------------------------------------
# The JWK is a single-line JSON value; compose passes it through verbatim.
{
    echo "# GENERATED by docker/certs/generate-dev-secrets.sh — local development only."
    echo "# Every value here is a private key or signing secret. Never commit this file."
    echo "WEBE_JWT_SIGNING_KEY=$(cat jwt-signing-key.json)"
    echo "DAO_SERVICE_TOKEN=${DAO_SERVICE_TOKEN}"
    echo "WORKFLOW_SERVICE_TOKEN=${WORKFLOW_SERVICE_TOKEN}"
    echo "DPS_SERVICE_TOKEN=${DPS_SERVICE_TOKEN}"
    echo "WEBE_WORKLOAD_SIGNING_KEY=${WORKLOAD_SIGNING_KEY}"
    echo "DPS_WORKLOAD_SIGNING_KEY=${WORKLOAD_SIGNING_KEY}"
    echo "WEBE_WORKLOAD_DPS_KEY=${WORKLOAD_SIGNING_KEY}"
    echo "WEBE_MARKETPLACE_CREDENTIAL_KEY=${MARKETPLACE_CREDENTIAL_KEY}"
    # Carried over from .env so one --env-file covers the whole stack.
    echo "POSTGRES_USER=influencercrm_user"
    echo "POSTGRES_PASSWORD=password"
    echo "POSTGRES_DB=influencercrm_db"
} > "$OUT_ENV"

echo
echo "Wrote docker/certs/$OUT_ENV"
echo "Start the stack with:"
echo "  docker compose --env-file docker/certs/$OUT_ENV -f docker-compose.yml -f docker-compose.hostports.yml up -d"
