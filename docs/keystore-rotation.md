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
