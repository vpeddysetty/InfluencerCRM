package com.influencer.dao.content.api;

import com.influencer.dao.content.domain.LandingTemplate;
import com.influencer.dao.content.infrastructure.LandingTemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/landing-templates")
public class LandingTemplateController {
    private final LandingTemplateRepository repository;

    public LandingTemplateController(LandingTemplateRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<LandingTemplate> findAll(
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID campaignId,
            @RequestParam(required = false) String publicSlug) {
        if (publicSlug != null) {
            return repository.findByPublicSlug(publicSlug).map(List::of).orElseGet(List::of);
        }
        if (brandId != null && campaignId != null) {
            return repository.findByBrandIdAndCampaignId(brandId, campaignId).map(List::of).orElseGet(List::of);
        }
        if (brandId != null) {
            return repository.findByBrandId(brandId);
        }
        if (campaignId != null) {
            return repository.findByCampaignId(campaignId);
        }
        return repository.findAll();
    }

    /**
     * Pages waiting on someone since before a cutoff (PR-44).
     *
     * <p>Its own endpoint rather than a filter on the list above, because the list is brand-scoped
     * and this deliberately is not: the abandonment sweep runs for the whole platform, and asking
     * it to enumerate brands first would turn one indexed query into one per brand.
     */
    @GetMapping("/awaiting-turn")
    public List<LandingTemplate> awaitingTurn(@RequestParam String before) {
        return repository.findAwaitingTurnSince(java.time.Instant.parse(before));
    }

    @GetMapping("/{id}")
    public LandingTemplate findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("LandingTemplate not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LandingTemplate create(@RequestBody LandingTemplate template) {
        return repository.save(template);
    }

    @PutMapping("/{id}")
    public LandingTemplate update(@PathVariable UUID id, @RequestBody LandingTemplate template) {
        LandingTemplate existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("LandingTemplate not found"));
        // Before anything is mutated: a refusal must leave the entity untouched, and a managed
        // entity mutated inside a transaction is flushed whether or not it is explicitly saved.
        requireCurrentVersion(template, existing);
        existing.setBrandId(template.getBrandId());
        existing.setCampaignId(template.getCampaignId());
        existing.setPublicSlug(template.getPublicSlug());
        existing.setName(template.getName());
        // blocks/theme are NOT NULL jsonb; a PUT that omits them must not null the
        // column — keep the incoming value, else the existing one, else the default.
        existing.setBlocks(firstNonNull(template.getBlocks(), existing.getBlocks(), "[]"));
        existing.setTheme(firstNonNull(template.getTheme(), existing.getTheme(), "{}"));
        // `document` is nullable by design (NULL = never opened in the visual builder), so
        // there is no default to fall back to — but a PUT that omits it must still not
        // erase a document the builder has already written.
        existing.setDocument(firstNonNull(template.getDocument(), existing.getDocument()));
        // PR-39. Same guard, same reason: nullable by design (NULL = never authored in the
        // section editor), so a PUT that omits it must leave an authored page alone. Unguarded,
        // any legacy-shaped write — the hosting sweep, the scheduled-publish sweep, a stage change
        // from the Kanban board — would silently blank a section page and drop it back onto the
        // GrapesJS document underneath it.
        existing.setSections(firstNonNull(template.getSections(), existing.getSections()));
        existing.setStatus(template.getStatus());
        existing.setStage(firstNonNull(template.getStage(), existing.getStage(), "draft"));
        // Phase E. Null-guarded: a PUT that omits these must not clear a hosting window that
        // has already started, or a published page would silently become free forever.
        if (template.getHostingExpiresAt() != null) {
            existing.setHostingExpiresAt(template.getHostingExpiresAt());
        }
        if (template.getFirstPublishedAt() != null) {
            existing.setFirstPublishedAt(template.getFirstPublishedAt());
        }
        // M5.6. Deliberately NOT null-guarded like the two above: clearing this is a meaningful
        // operation. Extending hosting must reset it so the new deadline gets its own warnings,
        // and a guard would make that reset unexpressible — every extended page would then stay
        // permanently silent. The cost is that a PUT omitting the field clears it, which at worst
        // re-sends one warning; the guarded alternative loses them all.
        existing.setHostingWarningSentAtDays(template.getHostingWarningSentAtDays());
        // PR-35. Unguarded for the same reason as the line above: clearing it is a meaningful
        // operation, not an omission. The scheduler consumes a pending time by writing NULL, and a
        // null-guard would make that unexpressible — the page would publish once, keep its time,
        // and republish on every sweep thereafter.
        //
        // The cost is the mirror image: a PUT that omits the field cancels a pending schedule. That
        // is why every BFF caller writing this row restates it — see LandingService.saveTemplate,
        // which carries the existing value forward so an ordinary builder save does not silently
        // un-schedule a publish the user set.
        existing.setScheduledPublishAt(template.getScheduledPublishAt());
        // PR-40. Null-guarded, unlike scheduledPublishAt above, and the asymmetry is deliberate.
        // Clearing the turn IS meaningful -- publishing does it -- but it is expressed by the
        // caller sending an explicit null field, whereas most writers to this row (the hosting
        // sweep, the scheduled-publish sweep, an ordinary builder save) simply do not mention the
        // turn at all. Unguarded, every one of those would silently drop a page out of somebody's
        // "waiting on you" list. The publish path clears it by writing the column directly.
        if (template.getTurn() != null) {
            existing.setTurn(template.getTurn());
            existing.setTurnChangedAt(template.getTurnChangedAt() == null
                    ? java.time.Instant.now() : template.getTurnChangedAt());
        }
        // PR-44. Null-guarded for the same reason as `turn` above: every other writer to this row
        // -- the hosting sweep, the publish sweep, an ordinary save -- never mentions it, and
        // unguarded each of them would clear the stamp and re-arm a reminder that already fired.
        // It is never cleared explicitly; it goes stale on its own by being older than
        // turn_changed_at once the page changes hands.
        if (template.getHandoffReminderSentAt() != null) {
            existing.setHandoffReminderSentAt(template.getHandoffReminderSentAt());
        }
        return repository.save(existing);
    }

    /**
     * Refuse a write built on a stale read (OP-18).
     *
     * <p><b>Why this is an explicit comparison and not left to Hibernate.</b> This endpoint loads
     * the row and mutates the managed entity, so by the time it saves, the {@code @Version} it
     * holds is the CURRENT one — Hibernate would compare it against itself and always agree. The
     * annotation still earns its place: it guards the narrow window between this read and this
     * write, and it makes the column self-maintaining. But the collision that actually matters
     * here is a human one, minutes wide — a brand and a creator who both loaded the page before
     * either saved — and only the client's own claim about what it read can detect that.
     *
     * <p><b>A caller that sends no version is allowed through.</b> That is deliberate and it is a
     * trade: it keeps every existing writer working — the hosting sweep, the scheduled-publish
     * sweep, stage changes from the board — none of which read-then-write on a human timescale and
     * none of which would gain anything from a conflict they cannot resolve. The cost is that
     * concurrency protection is opt-in per caller, so the editors have to send it to get it.
     */
    private void requireCurrentVersion(LandingTemplate incoming, LandingTemplate existing) {
        Long claimed = incoming.getVersion();
        if (claimed == null) {
            return;
        }
        Long current = existing.getVersion();
        if (current != null && !claimed.equals(current)) {
            // 409, with BOTH numbers: the caller can then say something truthful rather than just
            // refusing — what it believed it was editing, and what is actually stored.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This page was changed by someone else while you were editing it. "
                            + "Your version: " + claimed + ", current version: " + current);
        }
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
