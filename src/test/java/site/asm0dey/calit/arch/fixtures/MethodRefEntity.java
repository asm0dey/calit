package site.asm0dey.calit.arch.fixtures;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Not an {@code @Entity}: an entity static that reaches persistence only through a method reference.
 */
public class MethodRefEntity extends PanacheEntityBase {
    public static List<PanacheEntityBase> byIds(List<Long> ids) {
        return ids.stream().<PanacheEntityBase>map(PanacheEntityBase::findById).toList();
    }
}
