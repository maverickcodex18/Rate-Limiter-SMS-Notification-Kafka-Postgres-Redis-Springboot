package com.example.RateLimitedSMSGateway.Services;

import com.example.RateLimitedSMSGateway.Entity.RedisEntity;
import com.example.RateLimitedSMSGateway.Repository.RedisRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class RedisService {
    private final RedisRepository redisRepository;

    //constructor injection
    public RedisService(RedisRepository redisRepository) {
        this.redisRepository = redisRepository;
    }

    //insert
    public RedisEntity insertToRedis(RedisEntity redisEntity){
        return redisRepository.save(redisEntity);
    }

    //query (fetch details by userId)
    public RedisEntity queryRedis(int userId){
        Optional<RedisEntity> redisEntity=redisRepository.findById(userId);
        return redisEntity.orElse(null);
    }

    //increment currentCount by 1
    public RedisEntity incrementCurrentCount(RedisEntity redisEntity){
        redisEntity.setCurrentCount(redisEntity.getCurrentCount()+1);
        deleteUser(redisEntity.getUserId());
        redisEntity = insertToRedis(redisEntity);
        return redisEntity;
    }

    //delete user
    public void deleteUser(int userId){
        redisRepository.deleteById(userId);
    }
}
