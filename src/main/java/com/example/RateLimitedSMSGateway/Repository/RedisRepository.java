package com.example.RateLimitedSMSGateway.Repository;

import com.example.RateLimitedSMSGateway.Entity.RedisEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RedisRepository extends CrudRepository<RedisEntity, Integer> {
}
