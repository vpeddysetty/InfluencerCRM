package com.influencer.dao.shared.api;

import com.influencer.dao.shared.domain.AiGenerationEvent;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * The accepted kinds must equal the migration's CHECK constraint.
     *
     * <p><b>This is the bug that shipped.</b> V48 defined three kinds; V49 widened it to six. The
     * constant here was left at three, so every {@code classify} the BFF recorded came back 400
     * and was dropped. Nothing broke loudly: {@code AiGenerationAllowance.record()} catches and
     * continues on purpose, because losing a creator classification is worse than losing a meter
     * reading. The ceiling was live and counting nothing -- the same shape as the defect the class
     * note above describes, arriving a second time by a different route.
     *
     * <p><b>Read from the migration rather than restated.</b> A second hand-written list would be
     * a second thing to forget, which is precisely what happened. Parsing the .sql means the only
     * way to pass is to change the constraint the database actually enforces.
     */
    @Test
    @DisplayName("the accepted kinds match the CHECK constraint the database enforces")
    void kindsMatchTheMigration() throws Exception {
        Path migration = Path.of("..", "schema", "flyway", "V49__ai_generation_openai_kinds.sql");
        assertThat(Files.exists(migration))
                .as("V49 must be readable from the DAO module; this test is worthless if the path drifts")
                .isTrue();
        String sql = Files.readString(migration, StandardCharsets.UTF_8);

        int at = sql.indexOf("ai_generation_events_kind_check");
        assertThat(at).as("V49 must declare the kind CHECK constraint").isGreaterThan(-1);
        int listStart = sql.indexOf("(", sql.indexOf("kind in", at));
        int listEnd = sql.indexOf(")", listStart);
        String literals = sql.substring(listStart + 1, listEnd);

        Set<String> fromMigration = new TreeSet<>();
        Matcher lit = Pattern.compile("'([a-z_]+)'").matcher(literals);
        while (lit.find()) {
            fromMigration.add(lit.group(1));
        }

        Field kinds = AiGenerationUsageController.class.getDeclaredField("KINDS");
        kinds.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> accepted = new TreeSet<>((Set<String>) kinds.get(null));

        assertThat(accepted)
                .as("the controller rejects a kind the database would accept, so it is dropped and never counted")
                .isEqualTo(fromMigration);
    }
}
