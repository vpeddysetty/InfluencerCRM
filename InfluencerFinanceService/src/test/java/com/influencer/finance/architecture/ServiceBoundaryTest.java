package com.influencer.finance.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Keeps the extracted service from re-growing the dependencies it was extracted to remove.
 *
 * <p>Extraction is not a one-time event: the easiest way to undo it is for someone to add a
 * convenient import back to the monolith. These rules make that a build failure.
 */
class ServiceBoundaryTest {

    private static final String BASE = "com.influencer.finance";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("the service never depends on the monolith")
    void doesNotDependOnMonolith() {
        noClasses()
                .that().resideInAPackage(BASE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.influencer.dao..",
                        "com.influencer.webe..")
                .because("this context was extracted; depending on the monolith would undo that")
                .check(productionClasses);
    }

    @Test
    @DisplayName("the service never reaches another context's internals")
    void doesNotDependOnOtherContexts() {
        noClasses()
                .that().resideInAPackage(BASE + "..")
                // The ports package holds copied interfaces — published contracts, not internals.
                .and().resideOutsideOfPackage(BASE + ".ports..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.influencer.identity..",
                        "com.influencer.creator..",
                        "com.influencer.campaign..",
                        "com.influencer.attribution..",
                        "com.influencer.content..",
                        "com.influencer.workflow..")
                .because("cross-context access goes over the wire through a published contract, "
                        + "never a direct type reference")
                .check(productionClasses);
    }

    @Test
    @DisplayName("domain carries no web or Spring-wiring concerns")
    void domainStaysFrameworkLight() {
        noClasses()
                .that().resideInAPackage(BASE + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.stereotype..",
                        "org.springframework.data..")
                .because("domain must not depend on web, Spring stereotypes or Spring Data")
                .check(productionClasses);
    }

    @Test
    @DisplayName("nothing depends on a controller")
    void nothingDependsOnApi() {
        noClasses()
                .that().resideOutsideOfPackage(BASE + ".api..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".api..")
                .because("controllers are entry points; anything depending on one has the "
                        + "dependency arrow backwards")
                .check(productionClasses);
    }
}
