package com.influencer.workflow.api;

import com.influencer.workflow.domain.WorkflowBoardStage;
import com.influencer.workflow.infrastructure.WorkflowBoardStageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/workflow-board-stages")
public class WorkflowBoardStageController {
    private final WorkflowBoardStageRepository repository;

    public WorkflowBoardStageController(WorkflowBoardStageRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<WorkflowBoardStage> findAll(@RequestParam(required = false) UUID brandId,
                                            @RequestParam(required = false) UUID boardId) {
        if (brandId != null && boardId != null) {
            return repository.findByBrandIdAndBoardIdOrderByPositionAsc(brandId, boardId);
        }
        if (boardId != null) {
            return repository.findByBoardIdOrderByPositionAsc(boardId);
        }
        if (brandId != null) {
            return repository.findByBrandIdOrderByPositionAsc(brandId);
        }
        return repository.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowBoardStage create(@RequestBody WorkflowBoardStage stage) {
        applyDefaults(stage);
        return repository.save(stage);
    }

    @PutMapping("/{id}")
    public WorkflowBoardStage update(@PathVariable UUID id, @RequestBody WorkflowBoardStage stage) {
        WorkflowBoardStage existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("WorkflowBoardStage not found"));
        existing.setBrandId(stage.getBrandId());
        existing.setBoardId(stage.getBoardId());
        existing.setStageName(stage.getStageName());
        existing.setPosition(stage.getPosition());
        applyDefaults(existing);
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }

    /**
     * Replace the full ordered stage set for a single board. Used by the
     * customizable-stages editor so stage names/order are saved atomically.
     *
     * <p><b>Stage identity is preserved.</b> An earlier implementation deleted every stage on
     * the board and re-inserted with {@code setId(null)}, which minted fresh ids each time.
     * Because {@code workflow_cards.stage_id} is {@code on delete set null}, that silently
     * unplaced every card on the board — renaming one stage would orphan cards sitting in a
     * completely different one, with no error shown to the user.
     *
     * <p>A stage that arrives carrying an id is therefore <b>updated in place</b>, so cards
     * pointing at it keep pointing at it. Only stages the caller actually removed are deleted,
     * and only those cards are unplaced.
     */
    @PutMapping("/replace")
    @Transactional
    public List<WorkflowBoardStage> replace(@RequestBody ReplaceRequest request) {
        if (request == null || request.brandId == null || request.boardId == null) {
            throw new IllegalArgumentException("brandId and boardId are required.");
        }

        Map<UUID, WorkflowBoardStage> existingById = new LinkedHashMap<>();
        for (WorkflowBoardStage stage : repository.findByBoardIdOrderByPositionAsc(request.boardId)) {
            existingById.put(stage.getId(), stage);
        }

        List<WorkflowBoardStage> payload = request.stages == null ? new ArrayList<>() : request.stages;
        List<WorkflowBoardStage> saved = new ArrayList<>();
        Set<UUID> keptIds = new HashSet<>();

        for (int i = 0; i < payload.size(); i++) {
            WorkflowBoardStage incoming = payload.get(i);
            if (incoming == null || incoming.getStageName() == null || incoming.getStageName().isBlank()) {
                continue;
            }
            int position = incoming.getPosition() == null ? i : incoming.getPosition();

            // An id that belongs to THIS board identifies a stage to update. An id from another
            // board is ignored rather than honoured — otherwise a caller could rename or move a
            // stage it does not own by quoting its id.
            WorkflowBoardStage target = incoming.getId() == null ? null : existingById.get(incoming.getId());
            if (target != null) {
                target.setStageName(incoming.getStageName());
                target.setPosition(position);
                applyDefaults(target);
                keptIds.add(target.getId());
                saved.add(repository.save(target));
            } else {
                WorkflowBoardStage created = new WorkflowBoardStage();
                created.setBrandId(request.brandId);
                created.setBoardId(request.boardId);
                created.setStageName(incoming.getStageName());
                created.setPosition(position);
                applyDefaults(created);
                WorkflowBoardStage persisted = repository.save(created);
                keptIds.add(persisted.getId());
                saved.add(persisted);
            }
        }

        // Delete only what the caller dropped. Cards in a genuinely removed stage are unplaced
        // by the FK, which is correct — that stage no longer exists.
        for (Map.Entry<UUID, WorkflowBoardStage> entry : existingById.entrySet()) {
            if (!keptIds.contains(entry.getKey())) {
                repository.deleteById(entry.getKey());
            }
        }
        return saved;
    }

    private void applyDefaults(WorkflowBoardStage stage) {
        if (stage.getStageName() == null || stage.getStageName().isBlank()) {
            stage.setStageName("Stage");
        } else {
            stage.setStageName(stage.getStageName().trim());
        }
        if (stage.getPosition() == null || stage.getPosition() < 0) {
            stage.setPosition(0);
        }
    }

    public static class ReplaceRequest {
        public UUID brandId;
        public UUID boardId;
        public List<WorkflowBoardStage> stages;
    }
}
