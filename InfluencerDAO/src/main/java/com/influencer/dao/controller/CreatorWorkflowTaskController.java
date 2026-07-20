package com.influencer.dao.controller;

import com.influencer.dao.model.CreatorWorkflowTask;
import com.influencer.dao.repository.CreatorWorkflowTaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/creator-workflow-tasks")
public class CreatorWorkflowTaskController {
    private final CreatorWorkflowTaskRepository repository;

    public CreatorWorkflowTaskController(CreatorWorkflowTaskRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CreatorWorkflowTask> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID campaignCreatorId) {
        if (userId != null && campaignCreatorId != null) {
            return repository.findByUserIdAndCampaignCreatorId(userId, campaignCreatorId);
        }
        if (userId != null) {
            return repository.findByUserId(userId);
        }
        if (campaignCreatorId != null) {
            return repository.findByCampaignCreatorId(campaignCreatorId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public CreatorWorkflowTask findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("CreatorWorkflowTask not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatorWorkflowTask create(@RequestBody CreatorWorkflowTask task) {
        return repository.save(task);
    }

    @PutMapping("/{id}")
    public CreatorWorkflowTask update(@PathVariable UUID id, @RequestBody CreatorWorkflowTask task) {
        CreatorWorkflowTask existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("CreatorWorkflowTask not found"));
        existing.setUserId(task.getUserId());
        existing.setCampaignCreatorId(task.getCampaignCreatorId());
        existing.setTitle(task.getTitle());
        existing.setDescription(task.getDescription());
        existing.setAssigneeActor(task.getAssigneeActor());
        existing.setAssigneeCreatorId(task.getAssigneeCreatorId());
        existing.setStatus(task.getStatus());
        existing.setPriority(task.getPriority());
        existing.setDueAt(task.getDueAt());
        existing.setStartedAt(task.getStartedAt());
        existing.setCompletedAt(task.getCompletedAt());
        existing.setMetadata(task.getMetadata());
        existing.setCreatedByActor(task.getCreatedByActor());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
