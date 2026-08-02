package com.influencer.workflow.api;

import com.influencer.workflow.domain.WorkflowBoardStage;
import com.influencer.workflow.infrastructure.WorkflowBoardStageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
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
     */
    @PutMapping("/replace")
    @Transactional
    public List<WorkflowBoardStage> replace(@RequestBody ReplaceRequest request) {
        if (request == null || request.brandId == null || request.boardId == null) {
            throw new IllegalArgumentException("brandId and boardId are required.");
        }

        repository.deleteByBoardId(request.boardId);

        List<WorkflowBoardStage> payload = request.stages == null ? new ArrayList<>() : request.stages;
        List<WorkflowBoardStage> saved = new ArrayList<>();
        for (int i = 0; i < payload.size(); i++) {
            WorkflowBoardStage stage = payload.get(i);
            if (stage == null || stage.getStageName() == null || stage.getStageName().isBlank()) {
                continue;
            }
            stage.setId(null);
            stage.setBrandId(request.brandId);
            stage.setBoardId(request.boardId);
            if (stage.getPosition() == null) {
                stage.setPosition(i);
            }
            applyDefaults(stage);
            saved.add(repository.save(stage));
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
