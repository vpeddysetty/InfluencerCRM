package com.influencer.dao.controller;

import com.influencer.dao.model.CreatorWorkflowEvent;
import com.influencer.dao.repository.CreatorWorkflowEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/creator-workflow-events")
public class CreatorWorkflowEventController {
    private final CreatorWorkflowEventRepository repository;

    public CreatorWorkflowEventController(CreatorWorkflowEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CreatorWorkflowEvent> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID campaignCreatorId) {
        if (userId != null && campaignCreatorId != null) {
            return repository.findByUserIdAndCampaignCreatorIdOrderByCreatedAtDesc(userId, campaignCreatorId);
        }
        if (userId != null) {
            return repository.findByUserIdOrderByCreatedAtDesc(userId);
        }
        if (campaignCreatorId != null) {
            return repository.findByCampaignCreatorIdOrderByCreatedAtDesc(campaignCreatorId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public CreatorWorkflowEvent findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("CreatorWorkflowEvent not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatorWorkflowEvent create(@RequestBody CreatorWorkflowEvent event) {
        return repository.save(event);
    }

    @PutMapping("/{id}")
    public CreatorWorkflowEvent update(@PathVariable UUID id, @RequestBody CreatorWorkflowEvent event) {
        CreatorWorkflowEvent existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("CreatorWorkflowEvent not found"));
        existing.setUserId(event.getUserId());
        existing.setCampaignCreatorId(event.getCampaignCreatorId());
        existing.setActor(event.getActor());
        existing.setActorCreatorId(event.getActorCreatorId());
        existing.setEventType(event.getEventType());
        existing.setEventBody(event.getEventBody());
        existing.setEventData(event.getEventData());
        existing.setCreatedAt(event.getCreatedAt());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
