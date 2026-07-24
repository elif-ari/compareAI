package com.compareai.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Role {

    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}