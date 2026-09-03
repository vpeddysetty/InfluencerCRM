package com.influencer.dao.content.api;

import com.influencer.dao.content.domain.SharePost;
import com.influencer.dao.content.infrastructure.SharePostRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Creator post claims (roadmap PR-45).
 *
 * <p>Tenancy is enforced by the BFF, which is the only caller and which resolves the brand from a
 * verified token — the same arrangement every other controller here uses. This layer does not
 * re-derive it, because a brand id taken from a request body would be the thing to distrust.
 */
@RestController
@RequestMapping("/share-posts")
public class SharePostController {

    private final SharePostRepository repository;

    public SharePostController(SharePostRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<SharePost> findAll(@RequestParam UUID landingTemplateId) {
        return repository.findByLandingTemplateIdOrderByCreatedAtDesc(landingTemplateId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SharePost create(@RequestBody SharePost post) {
        // No update path, deliberately: a creator who posts, deletes and reposts has done two
        // things, and both are worth keeping. See the V50 header.
        post.setId(null);
        return repository.save(post);
    }
}
