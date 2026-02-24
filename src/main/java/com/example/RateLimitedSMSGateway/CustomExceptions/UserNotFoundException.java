package com.example.RateLimitedSMSGateway.CustomExceptions;

public class UserNotFoundException extends CustomExceptionTemplate{
    public UserNotFoundException() {
        super("User not found", 404);
    }
}
