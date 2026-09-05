# Keystore rotation (Phase 0.5)

**Status:** action required before any deployment
**Owner:** whoever operates the environment

---

## What happened

`InfluencerDAO/src/main/resources/keystore.p12` was committed to the repository. It is a PKCS12
keystore containing the DAO's **TLS private key**, and its password (`password`) was committed
alongside it in `application.properties`.

Anyone with any clone of this repository — including from its full history — holds the DAO's private
key and can impersonate the DAO or decrypt traffic to it.

## What has already been done

- The keystore is no longer tracked (`git rm --cached`).
- `.gitignore` now excludes `*.p12` / `*.jks`, with an explicit exemption for
  `dao-truststore.p12` (public certificate only — safe to commit).
- BFF → DAO calls now verify the certificate against that truststore instead of trusting all
  certificates.

**The key is still in git history and must be treated as compromised.** Untracking a file does not
remove it from previous commits.

## What still must be done

## Running the stack locally — the certificate pair must MATCH

**The symptom, and why it is hard to place.** The BFF answers `502 Unable to call DAO users
endpoint` for every request that reaches the DAO, and logs nothing about why: the TLS failure is
swallowed and only the gateway error survives. Signup, login and anything else touching the DAO
fail identically, which reads like a broken DAO rather than a certificate problem.

**The cause.** `dao-truststore.p12` is committed and holds a certificate for `CN=influencer-dao`.
The DAO's keystore is NOT committed — deliberately, it is a private key — so a developer generates
one, and unless it is generated with exactly the DN below the BFF refuses it. A keystore left in
`target/classes` from an earlier experiment (`CN=localhost`) will start the DAO happily and fail
every BFF call.

Diagnose by comparing the two:

```bash
keytool -list -v -keystore <the DAO keystore> -storepass password -storetype PKCS12 | grep -E "Owner:|SHA256:"
keytool -list -v -keystore InfluencerWebExperience/src/main/resources/dao-truststore.p12   -storepass changeit -storetype PKCS12 | grep -E "Owner:|SHA256:"
```

Different fingerprints mean the pair does not match, whatever the subjects say.

**LOCAL AND PRODUCTION ARE SEPARATE PAIRS, on purpose.** Production's keystore comes from Secrets
Manager and is never on a developer machine; the local pair is generated per machine and never
committed. Sharing one pair across both would put a production private key in a working tree — the
same mistake `docs/legal` had to unwind for the OpenAI key. Generate a local pair into
`.local-certs/` (gitignored) and point both services at it:

```bash
mkdir -p .local-certs
keytool -genkeypair -alias influencerdao -keyalg RSA -keysize 2048 -validity 825   -storetype PKCS12 -keystore .local-certs/keystore.p12 -storepass password -keypass password   -dname "CN=influencer-dao,OU=platform,O=influencrm,C=US"   -ext "SAN=dns:localhost,dns:influencer-dao,ip:127.0.0.1"

keytool -exportcert -alias influencerdao -keystore .local-certs/keystore.p12   -storetype PKCS12 -storepass password -file .local-certs/dao-cert.crt
keytool -importcert -noprompt -alias influencerdao -file .local-certs/dao-cert.crt   -keystore .local-certs/dao-truststore.p12 -storetype PKCS12 -storepass changeit
```

The SAN matters: without `dns:localhost` the certificate is trusted but hostname verification still
rejects `https://localhost:8443`.

Then run each service against the LOCAL pair, leaving the committed truststore untouched:

```bash
# DAO
DAO_KEYSTORE="file:/abs/path/.local-certs/keystore.p12" DAO_KEYSTORE_PASSWORD=password DAO_DB_URL="jdbc:postgresql://localhost:15432/influencercrm_db?stringtype=unspecified"   mvn -o spring-boot:run

# BFF
WEBE_DAO_TRUST_STORE="file:/abs/path/.local-certs/dao-truststore.p12" WEBE_DAO_TRUST_STORE_PASSWORD=changeit   mvn -o spring-boot:run -Dspring-boot.run.profiles=local
```

Note the database port: local Postgres publishes **15432**, not 5432. A DAO started against 5432
boots and then 500s on the first query.

---

### 1. Generate a new keystore (per environment)

Do **not** reuse the committed key. Generate a fresh one, and never commit it.

```bash
keytool -genkeypair \
  -alias influencerdao \
  -keyalg RSA -keysize 2048 -validity 825 \
  -storetype PKCS12 \
  -keystore keystore.p12 \
  -dname "CN=influencer-dao,OU=platform,O=influencrm,L=,S=,C=" \
  -ext "SAN=dns:localhost,dns:influencer-dao"
```

Store it outside the repository and supply it at runtime:

```properties
server.ssl.key-store=${DAO_KEYSTORE_PATH}
server.ssl.key-store-password=${DAO_KEYSTORE_PASSWORD}
```

For deployed environments prefer a CA-issued certificate (internal CA or ACME) over a self-signed
one — the BFF then needs no custom truststore at all.

### 2. Rebuild the BFF truststore

The truststore must contain the **new** certificate:

```bash
keytool -exportcert -alias influencerdao \
  -keystore keystore.p12 -storetype PKCS12 -storepass "$DAO_KEYSTORE_PASSWORD" \
  -file dao-cert.crt

keytool -importcert -noprompt -alias influencerdao \
  -file dao-cert.crt \
  -keystore InfluencerWebExperience/src/main/resources/dao-truststore.p12 \
  -storetype PKCS12 -storepass changeit
```

Only the public certificate goes in the truststore — never the private key.

### 3. Rotate the DAO service token

`dao.service-token` currently has a development default committed in `application.properties`. Set
`DAO_SERVICE_TOKEN` to a fresh value in every deployed environment, and the matching
`web-experience.dao-service-token` on the BFF.

```bash
openssl rand -base64 32
```

### 4. Set a persistent JWT signing key

**The BFF now refuses to start without one.** An ephemeral key cannot be verified by a second
instance or by an extracted service, and the resulting intermittent 401s read as a session bug
rather than a configuration one — so it fails loudly at boot instead.

#### Generating a JWT signing key

```bash
cat > GenKey.java <<'EOF'
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair; import java.security.KeyPairGenerator;
import java.security.interfaces.*; import java.util.UUID;
public class GenKey {
  public static void main(String[] a) throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
    KeyPair kp = g.generateKeyPair();
    System.out.println(new RSAKey.Builder((RSAPublicKey) kp.getPublic())
        .privateKey((RSAPrivateKey) kp.getPrivate())
        .keyID(UUID.randomUUID().toString()).build().toJSONString());
  }
}
EOF

CP=$(find ~/.m2/repository/com/nimbusds/nimbus-jose-jwt -name '*.jar' | head -1)
java -cp "$CP" GenKey.java
```

Set the output as `WEBE_JWT_SIGNING_KEY` (or `web-experience.jwt-signing-key`) in every deployed
environment. Treat it exactly like the TLS private key: never commit it, and rotating it logs every
user out.

For a single-process local run only, `web-experience.allow-ephemeral-jwt-key=true` restores the old
behaviour.

#### Rotating the JWT signing key without logging anyone out

Rotation used to be an outage: one key both signed and verified, so replacing it invalidated every
token already issued. That made rotation something to avoid — the wrong incentive for a credential
that should change regularly.

Two settings now separate the roles:

| Setting | Role |
|---|---|
| `web-experience.jwt-signing-key` | Signs new tokens, and verifies |
| `web-experience.jwt-previous-keys` | Verification only. Comma-separated **public** JWKs |

**The procedure — no user is signed out at any point:**

```bash
# 1. Generate the replacement (see above), then derive its public half for later.

# 2. Deploy with the new key active and the OLD key retained for verification.
#    Tokens already in the wild were signed by the old key and keep working.
WEBE_JWT_SIGNING_KEY='{new key, private}'
WEBE_JWT_PREVIOUS_KEYS='{old key, PUBLIC only}'

# 3. Confirm both keys are advertised:
curl -s http://localhost:8081/.well-known/jwks.json | jq '.keys | length'   # → 2

# 4. Wait one access-token lifetime (web-experience.access-token-ttl-minutes, default 30 min).
#    Every token signed by the old key has now expired.

# 5. Deploy again with the predecessor removed. Rotation complete.
WEBE_JWT_PREVIOUS_KEYS=''
```

Only the **public** half of a retired key belongs in `jwt-previous-keys`. Keeping its private half
would leave a credential able to sign tokens — and being unable to sign with it is the entire point
of rotating.

Step 5 is not optional. Until it happens the old key is still trusted, so the rotation has widened
the set of valid signers rather than replaced it.

Multiple predecessors are supported, which matters during an incident when two rotations may happen
in quick succession.

**Verified by `KeyRotationTest`:** a token issued before rotation still verifies afterwards; new
tokens are signed by the new key; retiring the predecessor rejects its tokens; an unrelated key is
rejected throughout; and the JWKS endpoint never publishes private material.

### 5. Purge the key from git history

Rewriting history is disruptive and coordinated — schedule it deliberately.

```bash
# Preferred: git-filter-repo
git filter-repo --path InfluencerDAO/src/main/resources/keystore.p12 --invert-paths
```

Everyone must then re-clone. **Rotation (step 1) is what actually protects you** — history purging
limits further exposure but does not undo it, since any existing clone still has the old key.

### 6. Rotate the OAuth client secrets if they were ever committed

`application-local.properties` holds live Google and Facebook client secrets. It is correctly
git-ignored and is **not** currently tracked, so no action is needed unless
`git log --all -- '**/application-local.properties'` returns commits. If it does, rotate both
secrets in the Google Cloud Console and Meta for Developers.

---

## Verification

```bash
# The keystore must be ignored...
git check-ignore -v InfluencerDAO/src/main/resources/keystore.p12

# ...and the truststore must NOT be (it holds only the public cert)
git check-ignore -v InfluencerWebExperience/src/main/resources/dao-truststore.p12   # expect: no match

# No keystore should be tracked anywhere
git ls-files | grep -E '\.(p12|jks)$'    # expect: only dao-truststore.p12
```
