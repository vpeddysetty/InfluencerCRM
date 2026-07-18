package com.influencer.dao.controller;

import com.influencer.dao.model.Interaction;
import com.influencer.dao.repository.InteractionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/interactions")
public class InteractionController {
    private final InteractionRepository repository;

    public InteractionController(InteractionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Interaction> findAll() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public Interaction findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("Interaction not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Interaction create(@RequestBody Interaction interaction) {
        return repository.save(interaction);
    }

    @PutMapping("/{id}")
    public Interaction update(@PathVariable UUID id, @RequestBody Interaction interaction) {
        Interaction existing = repository.findById(id).orElseThrow(() -> new RuntimeException("Interaction not found"));
        existing.setUserId(interaction.getUserId());
        existing.setCreatorId(interaction.getCreatorId());
        existing.setType(interaction.getType());
        existing.setBody(interaction.getBody());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
