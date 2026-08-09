package com.influencer.dao.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the bounded-context boundaries introduced in Phase 4.
 *
 * <p>Repackaging alone is only a convention: the first hurried change re-couples two contexts and
 * nobody notices until the extraction in Phase 5 turns out to be impossible. These rules make the
 * boundaries a build failure instead, which is the actual deliverable of Phase 4 — the packages are
 * just how it is expressed.
 *
 * <p>The permitted way across a boundary is a <em>published port</em>: an interface in the owning
 * context's {@code application} package, speaking in ids and primitives. Everything else —
 * entities, repositories, controllers — is private to its context.
 */
class ContextBoundaryTest {

    private static final String BASE = "com.influencer.dao";

    private static final String[] CONTEXTS = {
            "identity", "creator", "campaign", "workflow",
            "attribution", "finance", "content", "mapping"
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
            "attribution", "finance", "content", "mapping"})
    @DisplayName("a context may only cross a boundary through a published port")
    void contextsDoNotReachIntoOtherContexts(String context) {
        String[] foreignInternals = java.util.Arrays.stream(CONTEXTS)
                .filter(other -> !other.equals(context))
                // domain / infrastructure / api are private to their owning context.
                // application is the published-port package and is deliberately reachable.
                .flatMap(other -> java.util.stream.Stream.of(
                        BASE + "." + other + ".domain..",
                        BASE + "." + other + ".infrastructure..",
                        BASE + "." + other + ".api.."))
                .toArray(String[]::new);

        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + "." + context + "..")
                .should().dependOnClassesThat().resideInAnyPackage(foreignInternals)
                .because("crossing a context boundary must go through a published port in the "
                        + "owning context's application package, not its entities or repositories");

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("domain layers hold no persistence, web or Spring wiring concerns")
    void domainStaysFrameworkLight() {
        // JPA annotations are tolerated: the entities are still the persistence model, and
        // splitting them from ORM mappings is a larger change than Phase 4 promises. What must
        // not appear is Spring wiring or web machinery, which would tie domain to a runtime.
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + "..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.stereotype..",
                        "org.springframework.data..")
                .because("domain classes must not depend on web, Spring stereotypes or Spring Data");

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("controllers do not talk to repositories of another context")
    void apiDoesNotBypassItsOwnApplicationLayer() {
        for (String context : CONTEXTS) {
            String[] foreignRepos = java.util.Arrays.stream(CONTEXTS)
                    .filter(other -> !other.equals(context))
                    .map(other -> BASE + "." + other + ".infrastructure..")
                    .toArray(String[]::new);

            noClasses()
                    .that().resideInAPackage(BASE + "." + context + ".api..")
                    .should().dependOnClassesThat().resideInAnyPackage(foreignRepos)
                    .because("a controller reaching another context's repository bypasses that "
                            + "context's rules entirely")
                    .check(productionClasses);
        }
    }

    @Test
    @DisplayName("layering within a context flows api -> application -> infrastructure -> domain")
    void layersAreRespected() {
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Api").definedBy(BASE + "..api..")
                .layer("Application").definedBy(BASE + "..application..")
                .layer("Infrastructure").definedBy(BASE + "..infrastructure..")
                .layer("Domain").definedBy(BASE + "..domain..")

                // Nothing may depend on the web layer.
                .whereLayer("Api").mayNotBeAccessedByAnyLayer()
                // Application is reachable from Api and, as the published-port package, from
                // other contexts' application layers.
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Api", "Application")
                .check(productionClasses);
    }

    @Test
    @DisplayName("no context depends on the legacy flat packages")
    void legacyPackagesAreGone() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE + ".model..",
                        BASE + ".repository..",
                        BASE + ".controller..",
                        BASE + ".service..")
                .because("Phase 4 replaced the flat model/repository/controller/service packages "
                        + "with per-context modules; a reference to them means something was missed");

        rule.check(productionClasses);
    }
}
