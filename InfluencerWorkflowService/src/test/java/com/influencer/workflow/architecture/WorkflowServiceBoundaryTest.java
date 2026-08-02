package com.influencer.workflow.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Keeps the extracted service from growing the dependencies it was extracted to remove.
 *
 * <p>Extraction is not a one-time event: the easiest way to undo it is for someone to add a
 * convenient import back to the monolith. These rules make that a build failure.
 */
class WorkflowServiceBoundaryTest {

    private static final String BASE = "com.influencer.workflow";

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
                .because("this context was extracted; a dependency on the monolith would undo that "
                        + "and reintroduce the coupling the split removed")
                .check(productionClasses);
    }

    @Test
    @DisplayName("the service never reaches another context's tables or types")
    void doesNotDependOnOtherContexts() {
        noClasses()
                .that().resideInAPackage(BASE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.influencer.identity..",
                        "com.influencer.creator..",
                        "com.influencer.campaign..",
                        "com.influencer.attribution..",
                        "com.influencer.finance..",
                        "com.influencer.content..")
                .because("cross-context access must go through a published contract over the wire, "
                        + "never a direct type reference")
                .check(productionClasses);
    }

    @Test
    @DisplayName("layering flows api -> infrastructure -> domain")
    void layersAreRespected() {
        noClasses()
                .that().resideInAPackage(BASE + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE + ".api..",
                        BASE + ".infrastructure..")
                .because("domain is the innermost layer and must not know how it is stored or served")
                .check(productionClasses);

        noClasses()
                .that().resideOutsideOfPackage(BASE + ".api..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".api..")
                .because("controllers are entry points; anything depending on one has the dependency "
                        + "arrow backwards")
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
                .because("domain classes must not depend on web, Spring stereotypes or Spring Data")
                .check(productionClasses);
    }
}
