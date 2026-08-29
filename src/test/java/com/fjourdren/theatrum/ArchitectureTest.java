package com.fjourdren.theatrum;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The hexagonal rules of docs/architecture.md, enforced instead of grepped. Each rule maps to one
 * numbered check in that document — change one and change the other, or the doc starts lying.
 */
@AnalyzeClasses(packages = "com.fjourdren.theatrum", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String DOMAIN = "com.fjourdren.theatrum.domain..";
    private static final String APPLICATION = "com.fjourdren.theatrum.application..";
    private static final String INFRASTRUCTURE = "com.fjourdren.theatrum.infrastructure..";
    private static final String DOMAIN_SERVICE = "com.fjourdren.theatrum.domain.service..";
    private static final String ADAPTER = "com.fjourdren.theatrum.infrastructure.adapter..";
    private static final String CONFIG_ADAPTER = "com.fjourdren.theatrum.infrastructure.adapter.out.config..";
    private static final String PORT_IN = "com.fjourdren.theatrum.application.port.in";
    private static final String PORT_OUT = "com.fjourdren.theatrum.application.port.out";

    /** Rule 1+2 — no serialisation, mapping, metrics or CLI inside the hexagon. */
    @ArchTest
    static final ArchRule innerRingsUseNoFrameworks = noClasses()
            .that().resideInAnyPackage(DOMAIN, APPLICATION)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.fasterxml..", "org.mapstruct..", "io.micrometer..", "picocli..")
            .because("a config field name must never become a business rule (docs/architecture.md)");

    /** Rule 1b — Spring in the inner rings is wiring metadata only, never a type in a signature. */
    @ArchTest
    static final ArchRule innerRingsUseSpringForStereotypesOnly = noClasses()
            .that().resideInAnyPackage(DOMAIN, APPLICATION)
            .should().dependOnClassesThat(springBeyondStereotypes())
            .because("every domain test must stay a plain JUnit class that news its subject");

    private static DescribedPredicate<JavaClass> springBeyondStereotypes() {
        return resideInAPackage("org.springframework..")
                .and(not(equivalentTo(Component.class).or(equivalentTo(Autowired.class))))
                .as("Spring types other than @Component and @Autowired");
    }

    /** Rule 1c — constructor injection only. */
    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should().beAnnotatedWith(Autowired.class);

    /** Dependency direction: both inner rings ignore the outside world. */
    @ArchTest
    static final ArchRule domainDependsOnNothingOutward = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAPackage(INFRASTRUCTURE);

    @ArchTest
    static final ArchRule applicationDependsOnDomainOnly = noClasses()
            .that().resideInAPackage(APPLICATION)
            .should().dependOnClassesThat().resideInAnyPackage(INFRASTRUCTURE, DOMAIN_SERVICE)
            .because("ports are contracts: they know domain models, not who implements them");

    /** Rule 3 — adapters talk to ports. BeanConfig sits outside adapter/ precisely so it may not. */
    @ArchTest
    static final ArchRule adaptersDependOnPortsNotServices = noClasses()
            .that().resideInAPackage(ADAPTER)
            .should().dependOnClassesThat().resideInAPackage(DOMAIN_SERVICE);

    /** Rule 6 — domain services are built by component scan, never by hand. */
    @ArchTest
    static final ArchRule domainServicesAreBuiltByTheContainer = constructors()
            .that().areDeclaredInClassesThat().resideInAPackage(DOMAIN_SERVICE)
            .should().onlyBeCalled().byClassesThat().resideInAPackage(DOMAIN_SERVICE);

    /** Rule 7 — {@code *Yaml} wire types stay in the config adapter. */
    @ArchTest
    static final ArchRule wireTypesStayInTheConfigAdapter = noClasses()
            .that().resideOutsideOfPackage(CONFIG_ADAPTER)
            .should().dependOnClassesThat().resideInAPackage(CONFIG_ADAPTER + "entities..");

    /** Rule 8 — one driving adapter never reaches into another; shared code lives in ffmpeg/. */
    @ArchTest
    static final ArchRule drivingAdaptersAreIndependent = slices()
            .matching("com.fjourdren.theatrum.infrastructure.adapter.in.(*)..")
            .should().notDependOnEachOther();

    @ArchTest
    static final ArchRule drivenAdaptersIgnoreDrivingAdapters = noClasses()
            .that().resideInAPackage("com.fjourdren.theatrum.infrastructure.adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("com.fjourdren.theatrum.infrastructure.adapter.in..");

    /** Port naming and location, both ways: the package holds only ports, ports live nowhere else. */
    @ArchTest
    static final ArchRule drivingPortsAreUseCaseInterfaces = classes()
            .that().resideInAPackage(PORT_IN)
            .should().beInterfaces()
            .andShould().haveSimpleNameEndingWith("UseCase");

    @ArchTest
    static final ArchRule drivenPortsArePortInterfaces = classes()
            .that().resideInAPackage(PORT_OUT)
            .should().beInterfaces()
            .andShould().haveSimpleNameEndingWith("Port");

    @ArchTest
    static final ArchRule useCasesLiveInPortIn = classes()
            .that().haveSimpleNameEndingWith("UseCase")
            .should().resideInAPackage(PORT_IN);

    @ArchTest
    static final ArchRule portsLiveInPortOut = classes()
            .that().haveSimpleNameEndingWith("Port")
            .should().resideInAPackage(PORT_OUT);
}
