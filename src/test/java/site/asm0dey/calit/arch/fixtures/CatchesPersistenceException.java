package site.asm0dey.calit.arch.fixtures;

import jakarta.persistence.PersistenceException;

public class CatchesPersistenceException {
    Object run(Runnable r) {
        try {
            r.run();
            return null;
        } catch (PersistenceException e) {
            return e.getCause();
        }
    }
}
