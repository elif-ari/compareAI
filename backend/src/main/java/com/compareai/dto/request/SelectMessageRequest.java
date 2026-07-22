package com.compareai.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SelectMessageRequest {

    @NotNull
    private Long messageId;

}