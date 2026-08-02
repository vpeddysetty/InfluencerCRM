package com.influencer.dao.workflow.api;

import com.influencer.dao.workflow.domain.WorkflowBoard;
import com.influencer.dao.workflow.infrastructure.WorkflowBoardRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/workflow-boards")
public class WorkflowBoardController {
    private static final int MAX_BOARDS_PER_USER = 10;

    private final WorkflowBoardRepository repository;

    public WorkflowBoardController(WorkflowBoardRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<WorkflowBoard> findAll(@RequestParam(required = false) UUID brandId) {
        if (brandId != null) {
            return repository.findByBrandIdOrderByPositionAscCreatedAtAsc(brandId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public WorkflowBoard findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("WorkflowBoard not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public WorkflowBoard create(@RequestBody WorkflowBoard board) {
        if (board.getBrandId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "brandId is required.");
        }
        if (repository.countByBrandId(board.getBrandId()) >= MAX_BOARDS_PER_USER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Board limit reached. A user may have at most " + MAX_BOARDS_PER_USER + " boards.");
        }
        applyDefaults(board);
        board.setId(null);
        WorkflowBoard saved = repository.save(board);
        // If this board was created active, it becomes the single active board.
        if (Boolean.TRUE.equals(saved.getIsActive())) {
            deactivateOthers(saved.getBrandId(), saved.getId());
        }
        return saved;
    }

    @PutMapping("/{id}")
    @Transactional
    public WorkflowBoard update(@PathVariable UUID id, @RequestBody WorkflowBoard board) {
        WorkflowBoard existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("WorkflowBoard not found"));
        existing.setBrandId(board.getBrandId());
        existing.setName(board.getName());
        existing.setStartDate(board.getStartDate());
        existing.setEndDate(board.getEndDate());
        existing.setIsActive(board.getIsActive());
        existing.setPosition(board.getPosition());
        applyDefaults(existing);
        WorkflowBoard saved = repository.save(existing);
        if (Boolean.TRUE.equals(saved.getIsActive())) {
            deactivateOthers(saved.getBrandId(), saved.getId());
        }
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }

    private void deactivateOthers(UUID brandId, UUID keepBoardId) {
        if (brandId == null) {
            return;
        }
        List<WorkflowBoard> actives = repository.findByBrandIdAndIsActiveTrue(brandId);
        for (WorkflowBoard other : actives) {
            if (other.getId() != null && !other.getId().equals(keepBoardId)) {
                other.setIsActive(false);
                repository.save(other);
            }
        }
    }

    private void applyDefaults(WorkflowBoard board) {
        if (board.getName() == null || board.getName().isBlank()) {
            board.setName("Untitled board");
        } else {
            board.setName(board.getName().trim());
        }
        if (board.getIsActive() == null) {
            board.setIsActive(Boolean.FALSE);
        }
        if (board.getPosition() == null || board.getPosition() < 0) {
            board.setPosition(0);
        }
    }
}
