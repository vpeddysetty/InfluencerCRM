package com.influencer.workflow.api;

import com.influencer.workflow.domain.StageMapping;
import com.influencer.workflow.infrastructure.StageMappingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Page-stage -> board-stage mapping (roadmap D.6). Tenancy is enforced by the BFF. */
@RestController
@RequestMapping("/stage-mappings")
public class StageMappingController {
    private final StageMappingRepository repository;

    public StageMappingController(StageMappingRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<StageMapping> findAll(@RequestParam(required = false) UUID brandId,
                                      @RequestParam(required = false) UUID boardId,
                                      @RequestParam(required = false) String pageStage) {
        if (boardId != null && pageStage != null) {
            return repository.findByBoardIdAndPageStage(boardId, pageStage).map(List::of).orElseGet(List::of);
        }
        if (boardId != null) {
            return repository.findByBoardId(boardId);
        }
        if (brandId != null) {
            return repository.findByBrandId(brandId);
        }
        // An unfiltered call returns nothing rather than every tenant's mappings: the only
        // legitimate caller always knows its brand.
        return List.of();
    }

    /**
     * Upsert on (board, page_stage).
     *
     * A unique constraint covers that pair, so a plain insert would fail the second time a
     * brand adjusted a mapping. Upserting is what the mapping editor actually means by "save".
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StageMapping create(@RequestBody StageMapping mapping) {
        return repository.findByBoardIdAndPageStage(mapping.getBoardId(), mapping.getPageStage())
                .map(existing -> {
                    existing.setStageId(mapping.getStageId());
                    return repository.save(existing);
                })
                .orElseGet(() -> repository.save(mapping));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
