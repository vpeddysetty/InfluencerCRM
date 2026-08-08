package com.influencer.webe.content.infrastructure;

import com.influencer.webe.content.application.DomainRegistrarPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * A simulated registrar, used until a real Cloudflare or Route 53 account is wired up.
 *
 * <p>Phase E is the roadmap's highest-external-risk phase precisely because it depends on real
 * money, real DNS propagation and a real registrar contract — it is the one phase that cannot
 * be fully tested locally. This adapter lets the flow, the state machine and the expiry rules be
 * built and tested meanwhile.
 *
 * <p><b>It reports {@code provider = "mock"}.</b> Never "cloudflare". Same rule as the social
 * adapter in Phase C: a mock that claimed to be a real provider would put a simulated
 * verification in front of a brand as though DNS had genuinely been checked, and someone would
 * point a live domain at us on the strength of it.
 *
 * <p><b>Deterministic by domain name</b>, so tests can assert outcomes:
 * <ul>
 *   <li>a name containing {@code unverified} never verifies — the polling path stays testable</li>
 *   <li>a name containing {@code sslfail} verifies but fails certificate issuance, so the two
 *       states can be exercised independently</li>
 *   <li>everything else verifies and issues</li>
 * </ul>
 *
 * <p><b>No longer the default.</b> This bean previously carried {@code matchIfMissing = true}, so
 * an environment that simply had not set {@code web-experience.domains.provider} got a registrar
 * that verified every domain the caller did not deliberately name {@code unverified} — including
 * domains they did not own. The rule above ("everything else verifies") is correct for a test
 * double and catastrophic as a default: certificate issuance, host routing, and serving a page
 * under a customer's name all treat {@code dnsStatus = active} as proof of control.
 *
 * <p>Selecting it is now explicit. {@link DnsDomainRegistrar} performs a real lookup and is the
 * default; this one has to be asked for by name. See {@code application.properties}.
 */
@Component
@ConditionalOnProperty(name = "web-experience.domains.provider", havingValue = "mock")
public class MockDomainRegistrar implements DomainRegistrarPort {

    private final String hostingTarget;

    public MockDomainRegistrar(
            @Value("${web-experience.domains.hosting-target:pages.influencrm.example}") String hostingTarget) {
        this.hostingTarget = hostingTarget;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public Verification verifyDns(String domainName, String expectedToken) {
        String name = normalize(domainName);
        if (name.isEmpty() || expectedToken == null || expectedToken.isBlank()) {
            return new Verification(false, provider(), "Nothing to verify.");
        }
        if (name.contains("unverified")) {
            // The ordinary case a brand hits first: the record is not there yet.
            return new Verification(false, provider(),
                    "No matching TXT record found. DNS changes can take up to 48 hours to propagate.");
        }
        return new Verification(true, provider(), "TXT record found and matches (simulated).");
    }

    @Override
    public Certificate issueCertificate(String domainName) {
        String name = normalize(domainName);
        if (name.contains("sslfail")) {
            return new Certificate(false, provider(),
                    "The certificate authority refused this name (simulated).");
        }
        return new Certificate(true, provider(), "Certificate issued (simulated).");
    }

    @Override
    public Instructions instructionsFor(String domainName, String verificationToken) {
        String name = normalize(domainName);
        return new Instructions(
                "TXT  _influencrm-verify." + name + "  \"" + verificationToken + "\"",
                "CNAME  " + name + "  " + hostingTarget,
                "Add the TXT record first and verify, then point the domain with the CNAME. "
                        + "You keep ownership of the domain on your own registrar account — we only host the page.");
    }

    private String normalize(String domainName) {
        return domainName == null ? "" : domainName.trim().toLowerCase(Locale.ROOT);
    }
}
