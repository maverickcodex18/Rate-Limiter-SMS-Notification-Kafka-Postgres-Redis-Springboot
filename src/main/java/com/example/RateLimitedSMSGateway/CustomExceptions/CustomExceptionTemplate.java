package com.example.RateLimitedSMSGateway.CustomExceptions;

import lombok.Getter;

public class CustomExceptionTemplate extends RuntimeException{
    @Getter
    private final int statusCode;

    public CustomExceptionTemplate(String message,int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }
}
