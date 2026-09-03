package com.influencer.dao.content.api;

import com.influencer.dao.content.domain.PageLead;
import com.influencer.dao.content.infrastructure.PageLeadRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Public-page leads (roadmap PR-61).
 *
 * <p>Tenancy is enforced by the BFF, the only caller, which resolves the brand from a verified
 * token — the arrangement every controller here uses. A brand id taken from a request body is the
 * thing to distrust, and this layer never sees one it did not get from that path.
 */
@RestController
@RequestMapping("/page-leads")
public class PageLeadController {

    private final PageLeadRepository repository;

    public PageLeadController(PageLeadRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<PageLead> findAll(@RequestParam(required = false) UUID landingTemplateId,
                                  @RequestParam(required = false) String email) {
        if (email != null && !email.isBlank()) {
            // The erasure path: find every row for an address regardless of how it was typed.
            return repository.findByEmailIgnoringCase(email);
        }
        if (landingTemplateId == null) {
            return List.of();
        }
        return repository.findByLandingTemplateIdOrderByCreatedAtDesc(landingTemplateId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PageLead create(@RequestBody PageLead lead) {
        lead.setId(null);
        return repository.save(lead);
    }

    /**
     * Delete one lead.
     *
     * <p>A real delete rather than a soft one: this row exists because a member of the public gave
     * a brand their address, and an erasure request must leave nothing behind. A `deleted_at`
     * column would keep the very data the request is about.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
