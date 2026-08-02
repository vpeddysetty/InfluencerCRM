package com.influencer.webe.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces context boundaries in the BFF, mirroring the DAO's rules.
 *
 * <p>The DAO was split into contexts in Phase 4, but the BFF stayed layer-split — controllers in one
 * package, services in another. That asymmetry is what would have made extraction painful: a service
 * cannot move out cleanly while its API layer lives in a shared pile with six other contexts'.
 *
 * <p>The permitted route across a boundary is the same as in the DAO: a class in the owning context's
 * {@code application} package. Controllers and outbound clients are private to their context.
 */
class BffContextBoundaryTest {

    private static final String BASE = "com.influencer.webe";

    private static final String[] CONTEXTS = {
            "identity", "creator", "campaign", "workflow", "attribution", "finance", "content"
    };

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @ParameterizedTest(name = "{0} does not reach into another context''s internals")
    @ValueSource(strings = {"identity", "creator", "campaign", "workflow",
            "attribution", "finance", "content"})
    @DisplayName("a context may only cross a boundary through another context's application layer")
    void contextsDoNotReachIntoOtherContexts(String context) {
        String[] foreignInternals = Arrays.stream(CONTEXTS)
                .filter(other -> !other.equals(context))
                // api and infrastructure are private; application is the published surface.
                .flatMap(other -> Stream.of(
                        BASE + "." + other + ".api..",
                        BASE + "." + other + ".infrastructure.."))
                .toArray(String[]::new);

        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + "." + context + "..")
                .should().dependOnClassesThat().resideInAnyPackage(foreignInternals)
                .because("a context must not depend on another context's controllers or outbound "
                        + "clients — only on its application layer, which is the published surface");

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("controllers never call another context's outbound client directly")
    void apiDoesNotBypassApplicationLayers() {
        for (String context : CONTEXTS) {
            String[] foreignInfra = Arrays.stream(CONTEXTS)
                    .filter(other -> !other.equals(context))
                    .map(other -> BASE + "." + other + ".infrastructure..")
                    .toArray(String[]::new);

            noClasses()
                    .that().resideInAPackage(BASE + "." + context + ".api..")
                    .should().dependOnClassesThat().resideInAnyPackage(foreignInfra)
                    .because("a controller calling another context's client bypasses that context's "
                            + "rules entirely")
                    .check(productionClasses);
        }
    }

    @Test
    @DisplayName("nothing depends on a controller")
    void nothingDependsOnApi() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage(BASE + "..api..")
                .should().dependOnClassesThat().resideInAnyPackage(BASE + "..api..")
                .because("controllers are entry points; anything depending on one has the "
                        + "dependency arrow backwards");

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("no class depends on the legacy flat packages")
    void legacyPackagesAreGone() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE + ".controller..",
                        BASE + ".service..",
                        BASE + ".client..")
                .because("the flat controller/service/client packages were replaced by per-context "
                        + "modules; a reference to them means something was missed");

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("security and shared are reachable by everyone but depend on no context")
    void crossCuttingStaysCrossCutting() {
        String[] allContexts = Arrays.stream(CONTEXTS)
                .map(context -> BASE + "." + context + "..")
                .toArray(String[]::new);

        // If shared or security depended on a context, extracting that context would drag the
        // cross-cutting code with it — or break every other context that also uses it.
        noClasses()
                .that().resideInAPackage(BASE + ".security..")
                .should().dependOnClassesThat().resideInAnyPackage(allContexts)
                .because("security is cross-cutting and must not depend on any single context")
                .check(productionClasses);

        noClasses()
                .that().resideInAPackage(BASE + ".shared..")
                .should().dependOnClassesThat().resideInAnyPackage(allContexts)
                .because("shared is cross-cutting and must not depend on any single context")
                .check(productionClasses);
    }
}
