package site.asm0dey.calit.arch;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import module java.base;
import module java.sql;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import org.hibernate.Session;

/**
 * The persistence boundary: which calls count as database access, and which classes may make them.
 */
public final class PersistenceAccess {
    private static final List<Class<?>> PERSISTENCE_TYPES = List.of(
            EntityManager.class,
            EntityManagerFactory.class,
            Query.class,
            Session.class,
            Panache.class,
            PanacheQuery.class,
            DataSource.class,
            Connection.class,
            Statement.class,
            ResultSet.class
    );
    /**
     * Repository calls a service may make: by-id lookups and lifecycle of a loaded entity.
     */
    private static final Set<String> REPOSITORY_ALLOWLIST =
            Set.of("findById", "findByIdOptional", "persist", "persistAndFlush", "flush", "isPersistent");
    private static final DescribedPredicate<JavaClass> ASSIGNABLE_TO_REPOSITORY =
            assignableTo(PanacheRepositoryBase.class);
    /**
     * A repository, or a nested/anonymous class inside one.
     */
    public static final DescribedPredicate<JavaClass> IS_REPOSITORY = DescribedPredicate.describe("a Panache repository", c -> Stream
        .iterate(Optional.of(c), Optional::isPresent, o -> o.get().getEnclosingClass())
        .map(Optional::get)
        .anyMatch(ASSIGNABLE_TO_REPOSITORY));
    public static final ArchCondition<JavaClass> ACCESS_PERSISTENCE = new ArchCondition<>("access persistence") {
        @Override
        public void check(JavaClass item, ConditionEvents events) {
            for (JavaCodeUnitAccess<?> access : item.getCodeUnitAccessesFromSelf()) {
                if (accessesPersistence(access.getTarget(), new HashSet<>())) {
                    var message = "%s accesses persistence via %s in %s"
                        .formatted(
                                access.getOrigin().getFullName(),
                                access.getTarget().getFullName(),
                                access.getSourceCodeLocation()
                        );
                    events.add(SimpleConditionEvent.satisfied(access, message));
                }
            }
        }
    };

    private PersistenceAccess() {
    }

    private static boolean accessesPersistence(CodeUnitAccessTarget target, Set<JavaCodeUnit> visited) {
        if (directAccess(target)) {
            return true;
        }
        // An entity's static finder is persistence access when it reaches one through its own statics.
        var member = target.resolveMember().orElse(null);
        if (member == null
                || !isStatic(member)
                || !member.getOwner().isAssignableTo(PanacheEntityBase.class)
                || !visited.add(member)) {
            return false;
        }
        return member.getCallsFromSelf().stream().anyMatch(call -> {
            var next = call.getTarget();
            boolean sameClassStatic =
                    next
                .resolveMember()
                .filter(m -> m.getOwner().equals(member.getOwner()) && isStatic(m))
                .isPresent();
            return sameClassStatic ? accessesPersistence(next, visited) : directAccess(next);
        });
    }

    /**
     * Rules 1-3: a persistence type, a PanacheEntityBase member, or a non-allowlisted repository member.
     */
    private static boolean directAccess(CodeUnitAccessTarget target) {
        if (PERSISTENCE_TYPES.stream().anyMatch(target.getOwner()::isAssignableTo)) {
            return true;
        }
        return target.resolveMember().map(m -> {
            var declaring = m.getOwner();
            return declaring.isEquivalentTo(PanacheEntityBase.class)
                    || declaring.isEquivalentTo(PanacheRepositoryBase.class) && !allowedRepositoryCall(m);
        }).orElse(false);
    }

    private static boolean isStatic(JavaCodeUnit unit) {
        return unit.getModifiers().contains(JavaModifier.STATIC);
    }

    private static boolean allowedRepositoryCall(JavaCodeUnit member) {
        return REPOSITORY_ALLOWLIST.contains(member.getName())
                || "delete".equals(member.getName()) && member.getRawParameterTypes().size() == 1;
    }
}
