package com.example.RateLimitedSMSGateway.DTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public class AllRecords {
    public record RateLimitConfigRequest(
            @Min(0) int userId,
            @Min(1) int rateLimit,
            @Min(1) int timeWindow
    ) {}
    public record RespondCreateUserConfig(
            int userId,
            int rateLimit,
            int timeWindow,
            int currentCount,
            Instant lastRefreshTime
    ){}


    public record SendRequest(
            @Min(0) int userId,
            @NotBlank String message
    ){}

}
