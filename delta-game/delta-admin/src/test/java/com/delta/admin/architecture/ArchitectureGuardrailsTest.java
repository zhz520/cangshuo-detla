package com.delta.admin.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * P0 guardrails. Existing violations are frozen in archunit_store; only new violations fail.
 */
@AnalyzeClasses(packages = "com.delta", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardrailsTest {

    @ArchTest
    static final ArchRule controllersMustNotDependOnMappers = FreezingArchRule.freeze(
            noClasses().that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Mapper"));

    @ArchTest
    static final ArchRule commonMustNotDependOnBusinessModules = FreezingArchRule.freeze(
            noClasses().that().resideInAPackage("com.delta.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.delta.admin..", "com.delta.user..", "com.delta.player..",
                            "com.delta.product..", "com.delta.order..", "com.delta.pay..",
                            "com.delta.message..", "com.delta.cs..", "com.delta.system.."));

    @ArchTest
    static final ArchRule businessModulesMustNotDependOnAdmin = FreezingArchRule.freeze(
            noClasses().that().resideInAnyPackage(
                            "com.delta.user..", "com.delta.player..", "com.delta.product..",
                            "com.delta.order..", "com.delta.pay..", "com.delta.message..",
                            "com.delta.cs..", "com.delta.system..")
                    .should().dependOnClassesThat().resideInAPackage("com.delta.admin.."));
}
