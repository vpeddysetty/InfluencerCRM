package com.influencer.dao.shared.api;

import com.influencer.dao.shared.domain.AiGenerationEvent;
import com.influencer.dao.shared.infrastructure.AiGenerationEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Records and counts billed AI calls (V48).
 *
 * <p>Two operations and nothing else: how many has this account made since an instant, and record
 * that it made another. The BFF owns the policy — what the allowance is, and what to do when it is
 * reached — because that is a plan question and plans live there.
 *
 * <p><b>Counting is a query, not a stored total.</b> A counter would need something to reset it at
 * the turn of the month, and a reset job that fails silently leaves accounts either locked out or
 * unmetered. Counting rows since a caller-supplied instant needs nothing to run at all, and the
 * caller decides what "this month" means.
 */
@RestController
public class AiGenerationUsageController {

    /**
     * Mirrors the check constraint in V49. Rejected here so a bad kind never reaches the insert.
     *
     * <p><b>Keep this in step with the migration.</b> V48 defined three kinds and V49 widened it to
     * six; this list was left at three, so every `classify` the BFF recorded came back 400 and the
     * allowance silently went uncounted for three days -- AiGenerationAllowance logs the failure
     * and continues, by design, because losing a creator classification is worse than losing a
     * meter reading. That design is right and it is also why nothing surfaced: the only evidence
     * was a WARN nobody was reading.
     *
     * <p>The DATABASE was never wrong here -- V49's constraint has allowed all six since it ran.
     * Only this mirror drifted, which is the failure mode a hand-maintained mirror has.
     */
    private static final Set<String> KINDS =
            Set.of("generate", "regenerate", "rewrite", "classify", "brief_draft", "column_mapping");

    private final AiGenerationEventRepository repository;

    public AiGenerationUsageController(AiGenerationEventRepository repository) {
        this.repository = repository;
    }

    /**
     * How many BILLED calls this account has made since {@code since}.
     *
     * <p>Template generations are recorded but never counted: they cost nothing, and charging them
     * against an allowance meant to cap spend would penalise an account for a fallback it did not
     * choose and cannot see.
     */
    @GetMapping("/ai-generation-usage")
    public Map<String, Object> usage(@RequestParam UUID accountId,
                                     @RequestParam(required = false) String since) {
        Instant from = parseSince(since);
        long used = repository.countBilledSince(accountId, from);
        return Map.of("accountId", accountId.toString(), "since", from.toString(), "used", used);
    }

    @PostMapping("/ai-generation-usage")
    @ResponseStatus(HttpStatus.CREATED)
    public AiGenerationEvent record(@RequestBody RecordRequest request) {
        if (request.accountId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountId is required");
        }
        String kind = request.kind() == null ? "" : request.kind().trim().toLowerCase();
        if (!KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "kind must be one of " + KINDS);
        }

        AiGenerationEvent event = new AiGenerationEvent();
        event.setAccountId(request.accountId());
        event.setBrandId(request.brandId());
        event.setKind(kind);
        event.setGenerator(request.generator() == null || request.generator().isBlank()
                ? "anthropic" : request.generator().trim());
        // Set here rather than left to the column default: created_at is `not null default now()`,
        // but a Postgres default applies only when the column is OMITTED from the INSERT, and
        // Hibernate always names every mapped field. This trap has cost this codebase four
        // separate outages -- campaign_creators, creators, creator_identities and its link table.
        event.setCreatedAt(Instant.now());
        return repository.save(event);
    }

    /**
     * The window start, defaulting to thirty days back.
     *
     * <p>The default is deliberately not "the start of the calendar month": this endpoint should
     * not decide the billing period. The BFF passes the boundary it means, and an absent one only
     * has to be a sane answer rather than the right one.
     */
    private Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return Instant.now().minus(30, ChronoUnit.DAYS);
        }
        try {
            return Instant.parse(since);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "since must be an ISO-8601 instant");
        }
    }

    public record RecordRequest(UUID accountId, UUID brandId, String kind, String generator) {
    }
}
