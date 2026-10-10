package com.acme.coldtrace.platform.monitoring;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

public class ArchitectureTest {
  @Test
  void domainHasNoFrameworkOrProviderDependencies() {
    var classes = new ClassFileImporter().importPackages("com.acme.coldtrace.platform");
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "jakarta.persistence..",
            "com.google.cloud..",
            "com.stripe..",
            "com.fasterxml.jackson..")
        .check(classes);
  }
}
