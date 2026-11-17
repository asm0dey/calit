package site.asm0dey.calit.arch.fixtures;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Not an {@code @Entity}: Hibernate and the Panache enhancer ignore it in {@code @QuarkusTest} boots.
 */
public class FixtureEntity extends PanacheEntityBase {
}
