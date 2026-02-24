package com.example.RateLimitedSMSGateway.Entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;
import org.springframework.data.redis.core.index.Indexed;

@Data
@AllArgsConstructor
@NoArgsConstructor
@RedisHash(value="RateLimiterCache")
public class RedisEntity {

    @Id
    @Indexed
    private int userId;

    private int rateLimit;
    private int currentCount;

    @TimeToLive
    private long ttl;
}
