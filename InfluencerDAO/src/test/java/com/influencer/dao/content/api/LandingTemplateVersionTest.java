package com.influencer.dao.content.api;

import com.influencer.dao.content.domain.LandingTemplate;
import com.influencer.dao.content.infrastructure.LandingTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A save built on a stale read is refused (roadmap OP-18).
 *
 * <p>Landing pages are the one row in this system with two editors by design — a brand and an
 * invited creator can hold the same page open, which is the entire point of the collaboration
 * feature. Without a version check the second save wins completely and the first person's work is
 * gone with no error and nothing on screen to notice.
 *
 * <p>The last test is the one that documents a real trade rather than a fix: a caller that sends
 * no version is allowed through, because the sweeps and the board write this row too and none of
 * them read-then-write on a human timescale.
 */
class LandingTemplateVersionTest {

    private static final UUID PAGE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private LandingTemplateRepository repository;
    private LandingTemplateController controller;

    @BeforeEach
    void setUp() {
        repository = mock(LandingTemplateRepository.class);
        controller = new LandingTemplateController(repository);
        when(repository.findById(PAGE_ID)).thenReturn(Optional.of(stored(7L)));
        when(repository.save(any(LandingTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("a save carrying the current version is accepted")
    void currentVersionSaves() {
        LandingTemplate saved = controller.update(PAGE_ID, incoming(7L));

        assertThat(saved).isNotNull();
        verify(repository).save(any(LandingTemplate.class));
    }

    @Test
    @DisplayName("a save carrying a stale version is refused with a conflict")
    void staleVersionIsRefused() {
        // The creator loaded the page at version 7, the brand saved (taking it to 8), and now the
        // creator saves. Before OP-18 this silently overwrote the brand's edit.
        assertThatThrownBy(() -> controller.update(PAGE_ID, incoming(6L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409")
                // Both numbers, so the caller can say what happened rather than only that it failed.
                .hasMessageContaining("Your version: 6")
                .hasMessageContaining("current version: 7");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a refused save leaves the stored row untouched")
    void refusedSaveMutatesNothing() {
        // The check runs before any setter, because a managed entity mutated inside a transaction
        // is flushed whether or not save() is called — a late check would refuse the request and
        // persist the change anyway.
        LandingTemplate stale = incoming(6L);
        stale.setName("Renamed by the loser of the race");

        assertThatThrownBy(() -> controller.update(PAGE_ID, stale))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(repository.findById(PAGE_ID).orElseThrow().getName())
                .isEqualTo("Winter trail");
    }

    @Test
    @DisplayName("a caller that sends no version is still allowed to write")
    void absentVersionIsAllowed() {
        // Deliberate, and the reason is worth pinning: the hosting sweep, the scheduled-publish
        // sweep and stage changes from the board all write this row without having read it as a
        // human editor would. Refusing them would break four working features to protect two that
        // can simply send the field. Concurrency protection is opt-in per caller.
        LandingTemplate saved = controller.update(PAGE_ID, incoming(null));

        assertThat(saved).isNotNull();
        verify(repository).save(any(LandingTemplate.class));
    }

    // ---- fixtures ------------------------------------------------------

    private LandingTemplate stored(Long version) {
        LandingTemplate template = new LandingTemplate();
        template.setId(PAGE_ID);
        template.setBrandId(UUID.randomUUID());
        template.setCampaignId(UUID.randomUUID());
        template.setPublicSlug("winter-trail");
        template.setName("Winter trail");
        template.setStatus("draft");
        template.setStage("draft");
        setVersion(template, version);
        return template;
    }

    private LandingTemplate incoming(Long version) {
        LandingTemplate template = stored(version);
        template.setName("Winter trail");
        return template;
    }

    /**
     * The entity deliberately has no {@code setVersion} — Hibernate owns the field, and a public
     * setter would let a caller send its own number and defeat the check. Reflection is the honest
     * way to build a fixture without widening the production API for a test's convenience.
     */
    private void setVersion(LandingTemplate template, Long version) {
        try {
            Field field = LandingTemplate.class.getDeclaredField("version");
            field.setAccessible(true);
            field.set(template, version);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("LandingTemplate.version is gone or renamed", e);
        }
    }
}
