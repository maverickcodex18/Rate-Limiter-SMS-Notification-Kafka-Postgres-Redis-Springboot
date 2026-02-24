package com.example.RateLimitedSMSGateway.CustomExceptions;

public class RateLimitExceededException extends CustomExceptionTemplate{
    public RateLimitExceededException() {
        super("Rate Limit Exceeded", 429);
    }
}
