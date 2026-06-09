package com.example.demo.service;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

@Component
public class PublicUrlGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public String generate() {
        byte[] bytes = new byte[12]; // 96 bits entropy
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}