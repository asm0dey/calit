package site.asm0dey.calit.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.calit.arch.PersistenceAccess.ACCESS_PERSISTENCE;
import static site.asm0dey.calit.arch.PersistenceAccess.IS_REPOSITORY;
import module java.base;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.arch.fixtures.*;
import site.asm0dey.calit.booking.Booking;

class PersistenceAccessTest {
    private static List<String> violations(Class<?>... fixtures) {
        // Booking is imported, not just resolved from the classpath: only imported classes record their calls,
        // which the transitive finder rule follows. The rule still checks only the fixtures.
        var classes =
                new ClassFileImporter()
            .importClasses(Stream.concat(Stream.of(fixtures), Stream.of(Booking.class)).toList());
        return noClasses()
            .that(not(IS_REPOSITORY))
            .and()
            .resideInAPackage("..arch.fixtures..")
            .should(ACCESS_PERSISTENCE)
            .allowEmptyShould(true)
            .evaluate(classes)
            .getFailureReport()
            .getDetails();
    }

    @Test
    void flagsInheritedPanacheStatic() {
        assertThat(violations(InheritedPanacheStatic.class))
            .anySatisfy(d -> assertThat(d).contains("via site.asm0dey.calit.booking.Booking.findById("));
    }

    @Test
    void flagsEntityInstancePersist() {
        assertThat(violations(EntityInstancePersist.class))
            .anySatisfy(d -> assertThat(d).contains("via site.asm0dey.calit.booking.Booking.persist("));
    }

    @Test
    void flagsEntityFinderTransitively() {
        assertThat(violations(EntityFinderTransitive.class))
            .anySatisfy(d -> assertThat(d).contains("via site.asm0dey.calit.booking.Booking.group("));
    }

    @Test
    void flagsEntityManagerCall() {
        assertThat(violations(EntityManagerCall.class))
            .anySatisfy(d -> assertThat(d).contains("via jakarta.persistence.EntityManager.flush("));
    }

    @Test
    void flagsRepositoryQueryString() {
        assertThat(violations(RepositoryQueryString.class))
            .anySatisfy(d -> assertThat(d).contains("via io.quarkus.hibernate.orm.panache.PanacheRepositoryBase.find("));
    }

    @Test
    void allowsRepositoryById() {
        assertThat(violations(RepositoryById.class)).isEmpty();
    }

    @Test
    void allowsPureEntityMethods() {
        assertThat(violations(PureEntityMethods.class)).isEmpty();
    }

    @Test
    void allowsCatchingPersistenceException() {
        assertThat(violations(CatchesPersistenceException.class)).isEmpty();
    }

    @Test
    void exemptsRepositories() {
        assertThat(violations(FixtureRepository.class, FixtureRepository.Nested.class)).isEmpty();
    }
}
