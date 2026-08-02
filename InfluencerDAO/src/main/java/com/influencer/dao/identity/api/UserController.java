package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.User;
import com.influencer.dao.identity.infrastructure.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserRepository repository;

    public UserController(UserRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<User> findAll() {
        return repository.findAll();
    }

    @GetMapping("/by-email")
    public User findByEmail(@RequestParam String email) {
        return repository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @GetMapping("/{id}")
    public User findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public User create(@RequestBody User user) {
        return repository.save(user);
    }

    @PutMapping("/{id}")
    public User update(@PathVariable UUID id, @RequestBody User user) {
        User existing = repository.findById(id).orElseThrow(() -> new RuntimeException("User not found"));
        existing.setEmail(user.getEmail());
        // password_hash is nullable now, so a blind copy is destructive: a PUT that simply omits
        // the field would erase the password and lock out anyone whose only other credential is a
        // provider link. Absent means "unchanged"; clearing a password is its own operation.
        if (user.getPasswordHash() != null && !user.getPasswordHash().isBlank()) {
            existing.setPasswordHash(user.getPasswordHash());
        }
        existing.setBrandName(user.getBrandName());
        existing.setCustomAttributes(user.getCustomAttributes());
        existing.setRole(user.getRole());
        existing.setPlan(user.getPlan());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
