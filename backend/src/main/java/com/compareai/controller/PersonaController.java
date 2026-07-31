package com.compareai.controller;

import com.compareai.dto.request.CreatePersonaRequest;
import com.compareai.dto.response.PersonaResponse;
import com.compareai.entity.Persona;
import com.compareai.repository.PersonaRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/personas")
@CrossOrigin
public class PersonaController {

    private final PersonaRepository personaRepository;

    public PersonaController(PersonaRepository personaRepository) {
        this.personaRepository = personaRepository;
    }

    @GetMapping
    public List<PersonaResponse> getAllPersonas() {
        return personaRepository.findAll().stream()
                .map(this::toPersonaResponse)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PersonaResponse> getPersonaById(@PathVariable Long id) {
        return personaRepository.findById(id)
                .map(p -> ResponseEntity.ok(toPersonaResponse(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public PersonaResponse createPersona(@Valid @RequestBody CreatePersonaRequest request) {
        Persona persona = Persona.builder()
                .name(request.getName().trim())
                .title(request.getTitle().trim())
                .description(request.getDescription())
                .systemPrompt(request.getSystemPrompt().trim())
                .icon(request.getIcon() != null ? request.getIcon().trim() : "Bot")
                .isDefault(request.isDefault())
                .build();

        if (request.isDefault()) {
            clearDefaultPersona();
        }

        Persona saved = personaRepository.save(persona);
        return toPersonaResponse(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PersonaResponse> updatePersona(@PathVariable Long id,
                                                         @Valid @RequestBody CreatePersonaRequest request) {
        return personaRepository.findById(id).map(persona -> {
            persona.setName(request.getName().trim());
            persona.setTitle(request.getTitle().trim());
            persona.setDescription(request.getDescription());
            persona.setSystemPrompt(request.getSystemPrompt().trim());
            if (request.getIcon() != null) {
                persona.setIcon(request.getIcon().trim());
            }

            if (request.isDefault() && !persona.isDefault()) {
                clearDefaultPersona();
            }
            persona.setDefault(request.isDefault());

            Persona saved = personaRepository.save(persona);
            return ResponseEntity.ok(toPersonaResponse(saved));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePersona(@PathVariable Long id) {
        if (!personaRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        personaRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void clearDefaultPersona() {
        personaRepository.findByIsDefaultTrue().ifPresent(p -> {
            p.setDefault(false);
            personaRepository.save(p);
        });
    }

    private PersonaResponse toPersonaResponse(Persona p) {
        return PersonaResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .title(p.getTitle())
                .description(p.getDescription())
                .systemPrompt(p.getSystemPrompt())
                .icon(p.getIcon())
                .isDefault(p.isDefault())
                .build();
    }
}
