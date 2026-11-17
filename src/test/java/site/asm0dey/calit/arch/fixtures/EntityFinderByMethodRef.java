package site.asm0dey.calit.arch.fixtures;

import module java.base;

public class EntityFinderByMethodRef {
    Object load() {
        return MethodRefEntity.byIds(List.of(1L));
    }
}
