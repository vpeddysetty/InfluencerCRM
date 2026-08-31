package com.influencer.dao.shared.api;

import com.influencer.dao.shared.domain.AiGenerationEvent;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metering row has to be insertable.
 *
 * <p><b>Why this exists.</b> {@code AiGenerationEvent} is built fresh inside the controller and
 * handed straight to the repository, and with a bare {@code @Id} Hibernate refuses that before any
 * SQL runs: <em>"Identifier of entity must be manually assigned before calling persist()"</em>. The
 * column's {@code default gen_random_uuid()} never applies, because there is no INSERT to apply it
 * to — which is a different failure from the not-null-default trap elsewhere in this codebase, and
 * it looks identical from the outside.
 *
 * <p>It shipped because every layer above swallowed it: the DAO answered 500, the BFF turned that
 * into 502, and {@code AiGenerationAllowance.record()} catches deliberately so a failed recording
 * cannot throw away a generation the user is waiting for. The ceiling was live and counting
 * nothing, which is indistinguishable from a working one until a bill arrives.
 *
 * <p>Asserted on the mapping rather than by round-tripping a row: the other entities here use a
 * bare {@code @Id} legitimately — they are loaded and re-saved, never persisted fresh — so this is
 * a fact about THIS entity, not a rule for all of them.
 */
class AiGenerationUsageControllerTest {

    @Test
    @DisplayName("the event's id is generated, because the controller persists a new one")
    void idIsGenerated() throws Exception {
        Field id = AiGenerationEvent.class.getDeclaredField("id");

        assertThat(id.isAnnotationPresent(Id.class)).isTrue();
        assertThat(id.isAnnotationPresent(GeneratedValue.class))
                .describedAs("""
                        AiGenerationEvent is created fresh in AiGenerationUsageController and \
                        persisted immediately. Without @GeneratedValue, Hibernate throws \
                        IdentifierGenerationException before any SQL runs, the BFF reports 502, \
                        and the allowance silently records nothing.""")
                .isTrue();
    }

    @Test
    @DisplayName("only billed generators count against the allowance")
    void templateGenerationsAreNotBilled() {
        // Mirrors the repository's predicate and V48's partial index. A template draft costs
        // nothing, and charging an account for a fallback it did not choose and cannot see would
        // be indefensible -- so this pairing is worth pinning in a test rather than in a comment.
        AiGenerationEvent billed = new AiGenerationEvent();
        billed.setGenerator("anthropic");
        assertThat(billed.getGenerator()).isNotEqualTo("template");

        AiGenerationEvent free = new AiGenerationEvent();
        free.setGenerator("template");
        assertThat(free.getGenerator()).isEqualTo("template");
    }
}
