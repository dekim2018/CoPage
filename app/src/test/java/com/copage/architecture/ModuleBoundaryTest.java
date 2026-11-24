package com.copage.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 모듈 경계 (docs/03-ARCHITECTURE.md 5.1, 진단 ARCH-1, docs/review/DECISIONS.md D-32). 모듈은 com.copage 바로 아래 패키지
 * (identity, workspace, permission, collab, web)다. 모듈 안은 domain, app, in, out, config 로 나눈다.
 */
class ModuleBoundaryTest {

    private static final List<String> MODULES =
            List.of("identity", "workspace", "permission", "collab", "comment", "notification");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.copage");

    @Test
    void 모듈_사이에는_순환이_없다() {
        slices().matching("com.copage.(*)..").should().beFreeOfCycles().check(CLASSES);
    }

    @Test
    void 다른_모듈은_app_과_domain_으로만_쓴다() {
        // in(입구), out(저장소 구현), config(설정)는 그 모듈 안에서만 쓴다. 다른 모듈은 포트와 서비스(app), 값(domain)만 본다.
        for (String module : MODULES) {
            noClasses()
                    .that().resideOutsideOfPackage("com.copage." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.copage." + module + ".in..",
                            "com.copage." + module + ".out..",
                            "com.copage." + module + ".config..")
                    .because(module + " 의 in, out, config 는 그 모듈 안의 것이다")
                    .check(CLASSES);
        }
    }

    @Test
    void domain_과_app_은_입구와_저장소_구현과_설정을_모른다() {
        // 포트와 어댑터: 바깥(in, out, config)이 안(app, domain)을 알고, 안은 바깥을 모른다.
        noClasses()
                .that().resideInAnyPackage("com.copage.*.domain..", "com.copage.*.app..")
                .should().dependOnClassesThat().resideInAnyPackage("com.copage.*.in..", "com.copage.*.out..", "com.copage.*.config..")
                .check(CLASSES);
    }

    @Test
    void domain_은_스프링을_모른다() {
        noClasses()
                .that().resideInAnyPackage("com.copage.*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .check(CLASSES);
    }
}
