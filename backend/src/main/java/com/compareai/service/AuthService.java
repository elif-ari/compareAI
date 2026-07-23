package com.compareai.service;

import com.compareai.dto.request.LoginRequest;
import com.compareai.dto.request.RegisterRequest;
import com.compareai.dto.response.AuthResponse;
import com.compareai.entity.AppUser;
import com.compareai.exception.EmailAlreadyExistsException;
import com.compareai.exception.InvalidCredentialsException;
import com.compareai.repository.AppUserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (appUserRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException(
                    "Bu e-posta ile zaten bir hesap var: " + normalizedEmail);
        }

        AppUser user = new AppUser();
        user.setName(request.getName().trim());
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        AppUser saved = appUserRepository.save(user);
        return toAuthResponse(saved);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        AppUser user = appUserRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new InvalidCredentialsException("E-posta veya parola hatalı."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("E-posta veya parola hatalı.");
        }

        return toAuthResponse(user);
    }

    private AuthResponse toAuthResponse(AppUser user) {
        return AuthResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .build();
    }
}
