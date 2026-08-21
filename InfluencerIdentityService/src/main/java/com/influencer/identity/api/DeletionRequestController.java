package com.influencer.identity.api;

import com.influencer.identity.application.AccountPurgeService;
import com.influencer.identity.domain.DeletionRequest;
import com.influencer.identity.infrastructure.DeletionRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Operator-driven data-deletion requests, per the process published at /data-deletion/.
 *
 * <p><b>Not a self-service endpoint.</b> The published process is "email us from your registered
 * address", because that address is how a request gets attributed — the page says outright that a
 * request we cannot attribute is one we must refuse. An operator confirms the sender, records the
 * request here, and executes it. Exposing this to end users unattributed would turn a deletion
 * right into a way to delete someone else's account.
 *
 * <p>Execution is split into {@code /acknowledge} and {@code /execute} rather than being one call,
 * because the page promises we confirm what will be deleted <em>before</em> acting, so the
 * acknowledgement has to be able to precede the purge.
 */
@RestController
@RequestMapping("/deletion-requests")
public class DeletionRequestController {

    private final DeletionRequestRepository repository;
    private final AccountPurgeService purgeService;

    public DeletionRequestController(DeletionRequestRepository repository,
                                     AccountPurgeService purgeService) {
        this.repository = repository;
        this.purgeService = purgeService;
    }

    /** The operator queue: open requests, oldest first. Anything old here is overdue. */
    @GetMapping("/open")
    public List<DeletionRequest> open() {
        return repository.findOpen();
    }

    @GetMapping("/{id}")
    public DeletionRequest findById(@PathVariable UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such deletion request: " + id));
    }

    /** Every request for an address, including ones whose user row is already purged. */
    @GetMapping("/by-email")
    public List<DeletionRequest> findByEmail(@RequestParam String email) {
        return repository.findBySubjectEmailIgnoreCaseOrderByRequestedAtDesc(email);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeletionRequest record(@RequestBody RecordRequest request) {
        return purgeService.record(request.email(), request.scope(), request.provider());
    }

    @PostMapping("/{id}/acknowledge")
    public DeletionRequest acknowledge(@PathVariable UUID id) {
        return purgeService.acknowledge(id);
    }

    @PostMapping("/{id}/refuse")
    public DeletionRequest refuse(@PathVariable UUID id, @RequestBody RefuseRequest request) {
        return purgeService.refuse(id, request.reason());
    }

    /**
     * Carries out the deletion.
     *
     * <p>{@code force} is only consulted for account-scoped requests, and only matters when the
     * subject owns a workspace. It is never defaulted to true: that flag is the requester's
     * explicit confirmation that deleting their account may take colleagues' data with it.
     */
    @PostMapping("/{id}/execute")
    public DeletionRequest execute(@PathVariable UUID id,
                                   @RequestParam(defaultValue = "false") boolean force) {
        DeletionRequest request = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such deletion request: " + id));

        if (DeletionRequest.SCOPE_PROVIDER.equals(request.getScope())) {
            return purgeService.purgeProviderData(id);
        }
        return purgeService.purgeAccount(id, force);
    }

    public record RecordRequest(String email, String scope, String provider) {}

    public record RefuseRequest(String reason) {}
}
