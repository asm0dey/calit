package site.asm0dey.calit.arch.fixtures;

import jakarta.persistence.EntityManager;

public class EntityManagerCall {
    void flush(EntityManager em) {
        em.flush();
    }
}
