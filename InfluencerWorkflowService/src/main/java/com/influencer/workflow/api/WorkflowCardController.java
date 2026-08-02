package com.influencer.workflow.api;

import com.influencer.workflow.domain.WorkflowCard;
import com.influencer.workflow.infrastructure.WorkflowCardRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/workflow-cards")
public class WorkflowCardController {
    private final WorkflowCardRepository repository;

    public WorkflowCardController(WorkflowCardRepository repository) {
        this.repository = repository;
    }

    /**
     * List cards. Filters (all optional):
     *   brandId  - scope to a user
     *   boardId - cards on a specific board
     *   board   - "none" to return only unassigned cards (no board)
     */
    @GetMapping
    public List<WorkflowCard> findAll(@RequestParam(required = false) UUID brandId,
                                      @RequestParam(required = false) UUID boardId,
                                      @RequestParam(required = false) String board) {
        if (brandId != null && boardId != null) {
            return repository.findByBrandIdAndBoardIdOrderByPositionAsc(brandId, boardId);
        }
        if (brandId != null && "none".equalsIgnoreCase(board)) {
            return repository.findByBrandIdAndBoardIdIsNullOrderByCreatedAtDesc(brandId);
        }
        if (brandId != null) {
            return repository.findByBrandIdOrderByCreatedAtDesc(brandId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public WorkflowCard findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("WorkflowCard not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowCard create(@RequestBody WorkflowCard card) {
        applyDefaults(card);
        card.setId(null);
        return repository.save(card);
    }

    @PutMapping("/{id}")
    public WorkflowCard update(@PathVariable UUID id, @RequestBody WorkflowCard card) {
        WorkflowCard existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("WorkflowCard not found"));
        existing.setBrandId(card.getBrandId());
        existing.setCampaignId(card.getCampaignId());
        existing.setCreatorId(card.getCreatorId());
        existing.setBoardId(card.getBoardId());
        existing.setStageId(card.getStageId());
        existing.setName(card.getName());
        existing.setStatus(card.getStatus());
        existing.setAgreedFee(card.getAgreedFee());
        existing.setFeeCurrency(card.getFeeCurrency());
        existing.setNotes(card.getNotes());
        existing.setTags(card.getTags());
        existing.setPosition(card.getPosition());
        applyDefaults(existing);
        return repository.save(existing);
    }

    /**
     * Place (or unplace) a card: sets board_id + stage_id + position only.
     * Used by drag-and-drop onto a board stage. Pass null board/stage to detach.
     */
    @PutMapping("/{id}/placement")
    public WorkflowCard placement(@PathVariable UUID id, @RequestBody PlacementRequest request) {
        WorkflowCard existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("WorkflowCard not found"));
        existing.setBoardId(request.boardId);
        existing.setStageId(request.stageId);
        if (request.position != null) {
            existing.setPosition(request.position);
        }
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }

    private void applyDefaults(WorkflowCard card) {
        if (card.getName() == null || card.getName().isBlank()) {
            card.setName("Untitled card");
        } else {
            card.setName(card.getName().trim());
        }
        if (card.getStatus() == null || card.getStatus().isBlank()) {
            card.setStatus("todo");
        }
        if (card.getFeeCurrency() == null || card.getFeeCurrency().isBlank()) {
            card.setFeeCurrency("USD");
        }
        if (card.getTags() == null) {
            card.setTags(new ArrayList<>());
        }
        if (card.getPosition() == null || card.getPosition() < 0) {
            card.setPosition(0);
        }
    }

    public static class PlacementRequest {
        public UUID boardId;
        public UUID stageId;
        public Integer position;
    }
}
