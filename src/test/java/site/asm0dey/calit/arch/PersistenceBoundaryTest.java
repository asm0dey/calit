package site.asm0dey.calit.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static site.asm0dey.calit.arch.PersistenceAccess.ACCESS_PERSISTENCE;
import static site.asm0dey.calit.arch.PersistenceAccess.IS_REPOSITORY;
import module java.base;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import site.asm0dey.calit.booking.MeetingHosts;

/**
 * Database access lives in repositories (#343). Existing violations are frozen in
 * {@code src/test/resources/archunit_store}; fixing one shrinks the store, a new one fails the build.
 */
@AnalyzeClasses(packages = "site.asm0dey.calit", importOptions = ImportOption.DoNotIncludeTests.class)
class PersistenceBoundaryTest {
    /**
     * Classes already moved onto repositories: checked strictly, never frozen.
     */
    static final List<Class<?>> STRICT = List.of(MeetingHosts.class);
    @ArchTest
    static final ArchRule noPersistenceOutsideRepositories = FreezingArchRule.freeze(noClasses()
        .that(not(IS_REPOSITORY))
        .should(ACCESS_PERSISTENCE)
        .as("persistence access lives in repositories")
    );
    @ArchTest
    static final ArchRule migratedClassesAreClean = noClasses()
        .that()
        .belongToAnyOf(STRICT.toArray(Class<?>[]::new))
        .should(ACCESS_PERSISTENCE)
        .allowEmptyShould(STRICT.isEmpty());
}
