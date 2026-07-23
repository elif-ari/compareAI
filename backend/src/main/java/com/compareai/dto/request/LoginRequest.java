package com.compareai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "E-posta boş olamaz.")
    private String email;

    @NotBlank(message = "Parola boş olamaz.")
    private String password;
}
