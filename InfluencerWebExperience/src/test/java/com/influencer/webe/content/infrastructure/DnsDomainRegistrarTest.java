package com.influencer.webe.content.infrastructure;

import com.influencer.webe.content.application.DomainRegistrarPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards domain verification against silently accepting a domain nobody proved they own.
 *
 * <p>The adapter this replaces verified any name that did not contain the literal string
 * {@code unverified}, so a brand could claim {@code google.com}. Everything downstream —
 * certificate issuance, host routing, serving a page under that name — treats
 * {@code dnsStatus = active} as proof of control, which is what made a substring check a security
 * defect rather than an unfinished feature.
 *
 * <p>These tests are deliberately offline. Asserting against real DNS would make the suite depend
 * on a network and on records outside this repo's control; the lookup itself is JDK code, and what
 * is worth guarding is the decision made about its result.
 */
class DnsDomainRegistrarTest {

    /** As shipped: the hosting target is still the unresolvable placeholder (M5.1 is not done). */
    private final DnsDomainRegistrar registrar =
            new DnsDomainRegistrar("pages.example.test", 1000, 0);

    /** As deployed once M5.1's DNS record and certificate exist. */
    private final DnsDomainRegistrar configured =
            new DnsDomainRegistrar("pages.tejdux.com", 1000, 0);

    @Test
    @DisplayName("provider is reported as dns, never as a certificate authority")
    void reportsProvider() {
        // Recorded on every domain row so a simulated check can never be mistaken for a real one.
        assertEquals("dns", registrar.provider());
    }

    @Test
    @DisplayName("a blank domain or token verifies nothing")
    void rejectsEmptyInput() {
        // Guarded before any lookup: an empty token would otherwise be compared against whatever
        // happens to be published, and an empty-vs-empty match would verify a domain outright.
        assertFalse(registrar.verifyDns("", "token").verified());
        assertFalse(registrar.verifyDns("example.com", "").verified());
        assertFalse(registrar.verifyDns("example.com", null).verified());
        assertFalse(registrar.verifyDns(null, "token").verified());
    }

    @Test
    @DisplayName("a domain with no challenge record does not verify")
    void unresolvableDomainDoesNotVerify() {
        // .invalid is reserved by RFC 2606 and guaranteed never to resolve, so this exercises the
        // real lookup path and its failure branch without depending on anyone's zone.
        DomainRegistrarPort.Verification result =
                registrar.verifyDns("nothing-here.invalid", "influencrm-verify-abc123");

        assertFalse(result.verified(), "a name that cannot resolve must never verify");
        assertEquals("dns", result.provider());
    }

    @Test
    @DisplayName("a domain someone else owns does not verify just because it exists")
    void realDomainWithoutOurTokenDoesNotVerify() {
        // The exact defect: the old adapter returned verified=true here. example.com resolves and
        // is emphatically not ours, so a true result would mean the platform believes this brand
        // controls it. Offline this still fails on the lookup; online it fails on the comparison.
        DomainRegistrarPort.Verification result =
                registrar.verifyDns("example.com", "influencrm-verify-not-a-real-token");

        assertFalse(result.verified(), "a domain we published no token on must never verify");
    }

    @Test
    @DisplayName("the challenge label matches the instructions given to the brand")
    void instructionsMatchTheNameActuallyQueried() {
        // If these drift, a brand publishes a record at one name while the platform reads another
        // and verification never succeeds — with nothing on screen explaining why.
        //
        // Uses a configured (non-placeholder) target so this exercises the normal instruction path;
        // the placeholder path is asserted separately below.
        DomainRegistrarPort.Instructions instructions =
                configured.instructionsFor("Brand.Example.COM", "influencrm-verify-xyz");

        assertTrue(instructions.verificationRecord().contains(DnsDomainRegistrar.CHALLENGE_PREFIX + "brand.example.com"),
                "the TXT instruction must name the same label verifyDns queries");
        assertTrue(instructions.verificationRecord().contains("influencrm-verify-xyz"));
        assertTrue(instructions.aliasRecord().contains("pages.tejdux.com"));
    }

    // ---- M5.1: an unresolvable hosting target must not look configured ----

    @Test
    @DisplayName("RFC 2606 reserved names are recognised as placeholders")
    void recognisesPlaceholderTargets() {
        // The shipped default is one of these. A brand who CNAMEs to a reserved name gets no error
        // from anywhere: the registrar accepts the record, DNS answers NXDOMAIN, the page never
        // loads. Recognising it here is the only layer that can say so.
        assertTrue(DnsDomainRegistrar.isPlaceholderTarget("pages.influencrm.example"));
        assertTrue(DnsDomainRegistrar.isPlaceholderTarget("pages.example.test"));
        assertTrue(DnsDomainRegistrar.isPlaceholderTarget("host.invalid"));
        assertTrue(DnsDomainRegistrar.isPlaceholderTarget("example.com"));
        assertTrue(DnsDomainRegistrar.isPlaceholderTarget("pages.example.com"));
        // Trailing dots are legal in a zone file and must not smuggle a placeholder through.
        assertTrue(DnsDomainRegistrar.isPlaceholderTarget("pages.influencrm.example."));
        assertTrue(DnsDomainRegistrar.isPlaceholderTarget("PAGES.INFLUENCRM.EXAMPLE"));
        // Unset is a placeholder too — there is still nothing to point at.
        assertTrue(DnsDomainRegistrar.isPlaceholderTarget(null));
        assertTrue(DnsDomainRegistrar.isPlaceholderTarget("   "));
    }

    @Test
    @DisplayName("a real hosting target is not flagged")
    void acceptsRealTargets() {
        // The decided M5.1 target, plus a plain one. Note this check cannot tell whether a
        // plausible name is actually provisioned — only that it is not a reserved placeholder.
        // Provisioning is a deployment step, not something the application can verify for itself.
        assertFalse(DnsDomainRegistrar.isPlaceholderTarget("pages.tejdux.com"));
        assertFalse(DnsDomainRegistrar.isPlaceholderTarget("d111111abcdef8.cloudfront.net"));
    }

    @Test
    @DisplayName("with no real target, the CNAME is withheld rather than pointing at nothing")
    void placeholderTargetWithholdsTheAliasRecord() {
        // The defect M5.1 names: instructions were handed out with a CNAME to an unresolvable
        // name. Giving no CNAME and saying why is better than giving one that cannot work — the
        // brand knows to wait instead of debugging their own zone.
        DomainRegistrarPort.Instructions instructions =
                registrar.instructionsFor("brand.com", "influencrm-verify-xyz");

        assertEquals("", instructions.aliasRecord(),
                "no CNAME should be offered while there is nothing to point at");
        assertTrue(instructions.note().toLowerCase().contains("not yet available"),
                "the note must say hosting is not ready: " + instructions.note());

        // Verification is unaffected — proving ownership works regardless of where pages are
        // served, so a brand can still complete the TXT step today.
        assertTrue(instructions.verificationRecord().contains("influencrm-verify-xyz"));
    }

    @Test
    @DisplayName("TXT values are unquoted and multi-chunk strings are rejoined")
    void unquotesTxtValues() {
        // JNDI returns TXT values quoted, and splits anything over 255 characters into several
        // quoted chunks. A token near that boundary would never match if the chunks were not
        // rejoined, and no test of the happy path would catch it because short tokens work.
        assertEquals("influencrm-verify-abc", DnsDomainRegistrar.unquote("\"influencrm-verify-abc\""));
        assertEquals("firstsecond", DnsDomainRegistrar.unquote("\"first\" \"second\""));
        // Unquoted input passes through, since not every resolver quotes.
        assertEquals("plain-value", DnsDomainRegistrar.unquote("plain-value"));
        assertEquals("trimmed", DnsDomainRegistrar.unquote("  trimmed  "));
    }

    @Test
    @DisplayName("certificate issuance says plainly that it is not wired to a CA")
    void certificateIssuanceIsHonestAboutBeingUnwired() {
        // Verification becoming real must not imply issuance did. Conflating them is how a
        // simulated certificate ends up in front of a customer.
        DomainRegistrarPort.Certificate cert = registrar.issueCertificate("example.com");

        assertEquals("dns", cert.provider());
        assertTrue(cert.detail().toLowerCase().contains("not yet"),
                "the detail must state that no certificate authority is wired up");
    }

    /**
     * The success branch, with the lookup stubbed.
     *
     * <p>Subclassing rather than reaching for real DNS: a published record that matches our token
     * only exists on a domain a customer controls, so there is nothing on the public internet to
     * assert against. Overriding the one I/O method keeps the comparison logic — which is what can
     * actually be wrong — under test.
     */
    private static DnsDomainRegistrar registrarReturning(java.util.List<String> txtValues) {
        return new DnsDomainRegistrar("pages.example.test", 1000, 0) {
            @Override
            java.util.List<String> lookupTxt(String fqdn) {
                return txtValues;
            }
        };
    }

    @Test
    @DisplayName("a published TXT value matching the token verifies the domain")
    void matchingRecordVerifies() {
        DomainRegistrarPort.Verification result =
                registrarReturning(java.util.List.of("influencrm-verify-abc123"))
                        .verifyDns("brand.example", "influencrm-verify-abc123");

        assertTrue(result.verified(), "an exact match must verify");
    }

    @Test
    @DisplayName("one matching record among several unrelated ones is enough")
    void matchesAlongsideOtherRecords() {
        // A real zone carries SPF and other vendors' challenges at the same name. Requiring ours
        // to be the only TXT record would fail every domain that already verifies with anyone else.
        DomainRegistrarPort.Verification result = registrarReturning(java.util.List.of(
                        "v=spf1 include:_spf.google.com ~all",
                        "some-other-vendor-verification=xyz",
                        "influencrm-verify-abc123"))
                .verifyDns("brand.example", "influencrm-verify-abc123");

        assertTrue(result.verified());
    }

    @Test
    @DisplayName("a near-miss token does not verify")
    void nearMissDoesNotVerify() {
        // Substring, prefix, and case variations must all fail. The old adapter's whole defect was
        // treating "close enough" as proof of control.
        for (String published : java.util.List.of(
                "influencrm-verify-abc12",          // truncated
                "influencrm-verify-abc1234",        // extra character
                "INFLUENCRM-VERIFY-ABC123",         // wrong case; TXT values are case-sensitive
                "prefix-influencrm-verify-abc123",  // ours embedded in something else
                "")) {
            DomainRegistrarPort.Verification result = registrarReturning(java.util.List.of(published))
                    .verifyDns("brand.example", "influencrm-verify-abc123");
            assertFalse(result.verified(), "must not verify against published value: " + published);
        }
    }

    @Test
    @DisplayName("a record that exists but does not match says so, rather than blaming propagation")
    void wrongValueGetsADistinctMessage() {
        // "Wait 48 hours" is the wrong advice when the record is already there and simply wrong —
        // it sends the brand away to wait instead of re-copying the token.
        DomainRegistrarPort.Verification result =
                registrarReturning(java.util.List.of("influencrm-verify-WRONG"))
                        .verifyDns("brand.example", "influencrm-verify-abc123");

        assertFalse(result.verified());
        assertTrue(result.detail().contains("does not match"),
                "the message must distinguish a wrong value from a missing one: " + result.detail());
    }
}
