package com.influencer.platform.workload;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Generates the Ed25519 keypair a service signs its workload tokens with.
 *
 * <p>Runnable directly, because the alternative is a README instructing operators to run an
 * {@code openssl genpkey} incantation and then convert the PEM to the base64 DER this code wants.
 * Every manual step there is a chance to paste the wrong half — and pasting the <em>private</em>
 * key where the public one belongs would distribute the ability to mint to every verifier, which
 * is precisely what the asymmetric scheme exists to prevent.
 *
 * <pre>
 *   java -cp InfluencerPlatformCommons.jar \
 *        com.influencer.platform.workload.WorkloadKeyPairGenerator web-experience
 * </pre>
 *
 * <p>The private key goes ONLY into the issuing service's own configuration. The public key goes to
 * every service that must verify that issuer, and is not a secret.
 */
public final class WorkloadKeyPairGenerator {

    private WorkloadKeyPairGenerator() {
    }

    /** A generated pair, already base64-encoded in the forms the configuration expects. */
    public record Pair(String privateKey, String publicKey) {
    }

    public static Pair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = generator.generateKeyPair();
            return new Pair(
                    // PKCS#8 for the private half, X.509 for the public — the encodings
                    // KeyFactory reads back in WorkloadToken.
                    Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to generate an Ed25519 keypair", failure);
        }
    }

    public static void main(String[] args) {
        String service = args.length > 0 ? args[0] : "service";
        Pair pair = generate();

        System.out.println();
        System.out.println("# Keypair for issuer: " + service);
        System.out.println("# PRIVATE — only in " + service + "'s own config. Never commit it.");
        System.out.println(service + ".workload.private-key=" + pair.privateKey());
        System.out.println();
        System.out.println("# PUBLIC — give to every service that verifies " + service + ".");
        System.out.println("# Not a secret; it cannot mint.");
        System.out.println("workload.public-key." + service + "=" + pair.publicKey());
        System.out.println();
    }
}
