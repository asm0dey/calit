package site.asm0dey.calit.domain;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import net.bytebuddy.utility.AsmClassReader;
import org.junit.jupiter.api.Test;

/**
 * Hibernate's entity enhancer shares one {@code Advice} per code template across Quarkus's parallel class-transformation
 * threads. On a JVM newer than Byte Buddy's {@code ClassFileVersion.latest()}, Byte Buddy reads that advice through the
 * JDK Class-File API, whose lazily-inflated constant-pool entries are not thread-safe: augmentation then fails at random
 * with {@code EnhancementException ... "descriptor" is null}. Byte Buddy must know the build JVM so it stays on ASM.
 */
class EntityEnhancerReaderTest {
    @Test
    void byteBuddyReadsAdviceWithAsmOnThisJvm() throws IOException {
        byte[] advice;
        try (var in = getClass()
            .getResourceAsStream("/org/hibernate/bytecode/enhance/internal/bytebuddy/CodeTemplates$TrackChange.class")) {
            advice = in.readAllBytes();
        }
        assertThat(AsmClassReader.Factory.Default.IMPLICIT.make(advice))
            .as(
                    "Byte Buddy does not support Java %s; bump net.bytebuddy:byte-buddy in pom.xml",
                    Runtime.version().feature()
            )
            .isInstanceOf(AsmClassReader.ForAsm.class);
    }
}
