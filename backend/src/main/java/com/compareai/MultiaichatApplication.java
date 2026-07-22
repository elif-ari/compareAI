package com.compareai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MultiaichatApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultiaichatApplication.class, args);
        System.out.println("Multi AI Chat Backend calisiyor -> http://localhost:8080");
    }

}
