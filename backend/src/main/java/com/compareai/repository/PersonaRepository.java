package com.compareai.repository;

import com.compareai.entity.Persona;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PersonaRepository extends JpaRepository<Persona, Long> {
    Optional<Persona> findByIsDefaultTrue();
    Optional<Persona> findByName(String name);
}
