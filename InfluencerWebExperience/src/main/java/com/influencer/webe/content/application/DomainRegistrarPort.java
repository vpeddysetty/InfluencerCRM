package com.influencer.webe.content.application;

/**
 * DNS verification and certificate issuance for a brand-owned domain (roadmap E.2).
 *
 * <p>A port because the choice between Cloudflare and Route 53 is a business decision that may
 * change, and it must not reach into the deployment engine. The roadmap is explicit that the
 * registrar sits behind a port for exactly this reason.
 *
 * <p><b>This port never buys a domain.</b> Decision #9: the brand purchases on their own
 * registrar account and the platform never resells. So there is no {@code purchase} method, no
 * price and no order — only "does this brand actually control this name, and can we serve it
 * over HTTPS". That removes a reseller agreement, markup logic, billing integration, and the
 * question of who owns a domain when a brand leaves.
 */
public interface DomainRegistrarPort {

    /** Which implementation answered, recorded so a simulated check is never mistaken for a real one. */
    String provider();

    /**
     * Check whether the brand has published the verification TXT record.
     *
     * @return the outcome; {@code verified=false} is an ordinary result, not an error — DNS
     *         propagation takes time and a brand will poll
     */
    Verification verifyDns(String domainName, String expectedToken);

    /**
     * Request a certificate for a verified domain.
     *
     * <p>Only meaningful once DNS is verified: issuing for a domain we cannot prove control of
     * is what certificate authorities exist to prevent.
     */
    Certificate issueCertificate(String domainName);

    /** The DNS records a brand must create. Shown verbatim, so they must be exact. */
    Instructions instructionsFor(String domainName, String verificationToken);

    record Verification(boolean verified, String provider, String detail) {}

    record Certificate(boolean issued, String provider, String detail) {}

    /**
     * @param verificationRecord the TXT record proving control
     * @param aliasRecord        where the domain should point once verified
     */
    record Instructions(String verificationRecord, String aliasRecord, String note) {}
}
