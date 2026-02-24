package com.example.RateLimitedSMSGateway.CustomExceptions;

public class KafkaPublishException extends CustomExceptionTemplate{
    public KafkaPublishException() {
        super("Kafka Producer Failed : Internal Server Error", 503);
    }
}
