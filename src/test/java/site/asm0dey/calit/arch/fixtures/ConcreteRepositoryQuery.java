package site.asm0dey.calit.arch.fixtures;

public class ConcreteRepositoryQuery {
    Object query(FixtureRepository repo) {
        return repo.find("x");
    }
}
