package com.influencer.dao.content.api;

import com.influencer.dao.content.domain.ShareToken;
import com.influencer.dao.content.infrastructure.ShareTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/share-tokens")
public class ShareTokenController {
    private final ShareTokenRepository repository;

    public ShareTokenController(ShareTokenRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<ShareToken> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID campaignId,
            @RequestParam(required = false) String token) {
        if (token != null) {
            return repository.findByToken(token).map(List::of).orElseGet(List::of);
        }
        if (userId != null && campaignId != null) {
            return repository.findByUserIdAndCampaignId(userId, campaignId);
        }
        if (userId != null) {
            return repository.findByUserId(userId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public ShareToken findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("ShareToken not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShareToken create(@RequestBody ShareToken shareToken) {
        return repository.save(shareToken);
    }

    @PutMapping("/{id}")
    public ShareToken update(@PathVariable UUID id, @RequestBody ShareToken shareToken) {
        ShareToken existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("ShareToken not found"));
        existing.setUserId(shareToken.getUserId());
        existing.setCampaignId(shareToken.getCampaignId());
        existing.setCreatorId(shareToken.getCreatorId());
        existing.setToken(shareToken.getToken());
        existing.setScope(shareToken.getScope());
        existing.setExpiresAt(shareToken.getExpiresAt());
        existing.setRevoked(shareToken.getRevoked());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
