package com.influencer.webe.content.infrastructure;

import com.influencer.webe.content.application.DomainRegistrarPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

/**
 * Domain verification by an actual DNS lookup.
 *
 * <p>Replaces the check {@link MockDomainRegistrar} performs, which verified any name that did not
 * contain the literal string {@code unverified} — including domains the caller does not own. A
 * brand could claim {@code google.com} and the platform would agree. That is a security defect
 * rather than a missing feature: everything downstream (certificate issuance, host routing, serving
 * a page under someone else's name) treats {@code dnsStatus = active} as proof of control.
 *
 * <p><b>What proves control.</b> The brand publishes a TXT record at
 * {@code _influencrm-verify.<domain>} containing the token this platform generated. Only someone
 * who can edit that zone can do it. The token is server-generated with 24 bytes of entropy and
 * never accepted from the caller, so it cannot be satisfied by a record that happened to be there
 * already.
 *
 * <p><b>JNDI's DNS provider, not a new dependency.</b> It ships with the JDK, speaks the one record
 * type needed, and honours the platform resolver. dnsjava would add a dependency for TXT lookups
 * the JDK already does. The tradeoff is that JNDI gives no DNSSEC signal — acceptable here because
 * the token is a bearer secret over a channel the attacker would have to already control to forge.
 *
 * <p><b>Certificate issuance is still simulated</b> and still reports {@code provider = "dns"}
 * rather than a CA name. Real issuance is M7 (ACME), needs a schema to hold the certificate and
 * key, and must not be implied by this adapter. Verification being real does not make issuance
 * real, and conflating them is how a simulated certificate ends up in front of a customer.
 */
@Component
@ConditionalOnProperty(name = "web-experience.domains.provider", havingValue = "dns")
public class DnsDomainRegistrar implements DomainRegistrarPort {

    private static final Logger log = LoggerFactory.getLogger(DnsDomainRegistrar.class);

    /** The label the token is published under. Must match {@link #instructionsFor} exactly. */
    static final String CHALLENGE_PREFIX = "_influencrm-verify.";

    private final String hostingTarget;
    private final int timeoutMs;
    private final int retries;

    public DnsDomainRegistrar(
            @Value("${web-experience.domains.hosting-target:pages.influencrm.example}") String hostingTarget,
            @Value("${web-experience.domains.dns-timeout-ms:3000}") int timeoutMs,
            @Value("${web-experience.domains.dns-retries:1}") int retries) {
        this.hostingTarget = hostingTarget;
        this.timeoutMs = timeoutMs;
        this.retries = retries;

        if (isPlaceholderTarget(hostingTarget)) {
            // WARN at startup, and the instructions say so too (see instructionsFor). A brand who
            // follows a CNAME to a reserved name points their domain at nothing and gets no error
            // from anyone — their registrar accepts the record, DNS resolves NXDOMAIN, and the
            // page simply never loads. The failure is silent at every layer except this one.
            log.warn("""
                    Hosting target "{}" is an RFC 2606 reserved name and CANNOT RESOLVE. Any brand \
                    following the CNAME instructions will point their domain at nothing. Set \
                    WEBE_HOSTING_TARGET to a real, already-provisioned hostname before inviting \
                    anyone to connect a domain.""", hostingTarget);
        }
    }

    /**
     * Whether the configured target is a placeholder that cannot resolve.
     *
     * <p>Matches the RFC 2606 reserved TLDs plus {@code .example} second-level names. These exist
     * precisely so that a placeholder fails loudly, and the default ships as one deliberately: a
     * plausible-but-unprovisioned hostname would be worse, because it fails the same way while
     * looking configured.
     */
    static boolean isPlaceholderTarget(String target) {
        if (target == null || target.isBlank()) {
            return true;
        }
        String name = target.trim().toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        return name.endsWith(".example") || name.endsWith(".invalid")
                || name.endsWith(".test") || name.endsWith(".localhost")
                || name.equals("example.com") || name.endsWith(".example.com");
    }

    @Override
    public String provider() {
        return "dns";
    }

    @Override
    public Verification verifyDns(String domainName, String expectedToken) {
        String name = normalize(domainName);
        if (name.isEmpty() || expectedToken == null || expectedToken.isBlank()) {
            return new Verification(false, provider(), "Nothing to verify.");
        }

        List<String> records;
        try {
            records = lookupTxt(CHALLENGE_PREFIX + name);
        } catch (NamingException e) {
            // A missing record and an unreachable resolver both mean "not verified", but they are
            // different problems for the brand: one is "wait for propagation", the other is ours.
            // NameNotFound is the ordinary case and must not be logged as an error.
            if (isNameNotFound(e)) {
                return new Verification(false, provider(),
                        "No TXT record found at " + CHALLENGE_PREFIX + name
                                + ". DNS changes can take up to 48 hours to propagate.");
            }
            log.warn("DNS lookup failed for {}{}: {}", CHALLENGE_PREFIX, name, e.toString());
            return new Verification(false, provider(),
                    "Could not reach DNS to check the record. This is a problem on our side — try again shortly.");
        }

        if (records.isEmpty()) {
            return new Verification(false, provider(),
                    "No TXT record found at " + CHALLENGE_PREFIX + name
                            + ". DNS changes can take up to 48 hours to propagate.");
        }

        // Any matching record is enough. A zone legitimately carries several TXT records at one
        // name (SPF, other vendors' challenges), and requiring ours to be the only one would fail
        // every domain that already verifies with anyone else.
        for (String record : records) {
            if (constantTimeEquals(record, expectedToken)) {
                return new Verification(true, provider(), "TXT record found and matches.");
            }
        }

        return new Verification(false, provider(),
                "A TXT record exists at " + CHALLENGE_PREFIX + name
                        + " but does not match the expected value. Check it was copied in full.");
    }

    @Override
    public Certificate issueCertificate(String domainName) {
        // Deliberately not implemented here. See the class note: real issuance is M7.
        return new Certificate(true, provider(),
                "Certificate issuance is not yet wired to a certificate authority; this domain is "
                        + "DNS-verified but not yet served over HTTPS.");
    }

    @Override
    public Instructions instructionsFor(String domainName, String verificationToken) {
        String name = normalize(domainName);

        // Verification is real and works regardless of the hosting target, so the TXT step is
        // always given as-is. Only the CNAME depends on somewhere to point at.
        String note = "Add the TXT record first and verify, then point the domain with the CNAME. "
                + "You keep ownership of the domain on your own registrar account — we only host the page.";

        if (isPlaceholderTarget(hostingTarget)) {
            // Say it here rather than only in the log. This string is copied verbatim into a
            // registrar's zone editor; a brand who follows it gets a domain that resolves to
            // nothing and no explanation from any layer of the stack. Telling them the target is
            // not ready costs nothing and is the difference between "not available yet" and an
            // afternoon spent debugging their own DNS.
            return new Instructions(
                    "TXT  " + CHALLENGE_PREFIX + name + "  \"" + verificationToken + "\"",
                    "",
                    "Custom-domain hosting is not yet available on this deployment — no hosting "
                            + "target is configured, so there is nothing to point a CNAME at yet. "
                            + "You can add the TXT record now to verify ownership; we will provide "
                            + "the CNAME when hosting goes live.");
        }

        return new Instructions(
                "TXT  " + CHALLENGE_PREFIX + name + "  \"" + verificationToken + "\"",
                "CNAME  " + name + "  " + hostingTarget,
                note);
    }

    /**
     * Reads every TXT value at a name.
     *
     * <p>JNDI returns TXT values quoted, and splits strings longer than 255 characters into
     * several quoted chunks that must be concatenated — a token near that boundary would otherwise
     * never match. Both are handled by {@link #unquote}.
     */
    // Package-private and non-final so a test can substitute the one I/O call and exercise the
    // comparison logic, which is the part that can actually be wrong. A record matching our token
    // exists only on a domain a customer controls, so there is nothing public to assert against.
    List<String> lookupTxt(String fqdn) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(DirContext.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        // Without these a lookup against an unreachable resolver hangs the request thread.
        env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(timeoutMs));
        env.put("com.sun.jndi.dns.timeout.retries", String.valueOf(retries));

        DirContext ctx = new InitialDirContext(env);
        try {
            Attributes attrs = ctx.getAttributes(fqdn, new String[] {"TXT"});
            Attribute txt = attrs.get("TXT");
            if (txt == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < txt.size(); i++) {
                Object value = txt.get(i);
                if (value != null) {
                    out.add(unquote(value.toString()));
                }
            }
            return out;
        } finally {
            try {
                ctx.close();
            } catch (NamingException ignored) {
                // Closing a context that already failed is not worth reporting over the result.
            }
        }
    }

    /**
     * Strips the quoting JNDI applies and rejoins multi-chunk strings.
     *
     * <p>A 300-character TXT value arrives as {@code "first 255 chars" "the rest"}; naive
     * unquoting would leave the inner quotes and the separating space embedded in the value.
     */
    static String unquote(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.contains("\"")) {
            return trimmed;
        }
        StringBuilder joined = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (inQuotes) {
                joined.append(c);
            }
        }
        return joined.toString();
    }

    /**
     * Length-independent comparison.
     *
     * <p>The token is a bearer secret that decides domain ownership, and this endpoint is
     * pollable, so a timing oracle here is worth closing even though remote timing attacks over
     * DNS-fetched values are impractical. Cheap, and removes the question.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] left = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }

    /** Whether the failure is simply "no such record" rather than a resolver problem. */
    private static boolean isNameNotFound(NamingException e) {
        return e instanceof javax.naming.NameNotFoundException;
    }

    private String normalize(String domainName) {
        return domainName == null ? "" : domainName.trim().toLowerCase(Locale.ROOT);
    }
}
