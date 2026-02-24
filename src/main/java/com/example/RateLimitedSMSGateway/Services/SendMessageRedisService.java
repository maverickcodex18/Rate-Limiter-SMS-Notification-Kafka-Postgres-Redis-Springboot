package com.example.RateLimitedSMSGateway.Services;

import com.example.RateLimitedSMSGateway.CustomExceptions.RateLimitExceededException;
import com.example.RateLimitedSMSGateway.Entity.PostgresEntity;
import com.example.RateLimitedSMSGateway.Entity.RedisEntity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class SendMessageRedisService {
    private final RedisService redisService;
    private final SendMessagePostgresService sendMessagePostgresService;
    private final KafkaConfig kafkaConfig;

    //constructor injection
    public SendMessageRedisService(RedisService redisService, SendMessagePostgresService sendMessagePostgresService, KafkaConfig kafkaConfig) {
        this.redisService = redisService;
        this.sendMessagePostgresService = sendMessagePostgresService;
        this.kafkaConfig = kafkaConfig;
    }

    public void checkRateLimitRedis(RedisEntity redisEntity){
        if(redisEntity.getRateLimit()<=redisEntity.getCurrentCount()) throw new RateLimitExceededException();
    }

    public RedisEntity updateCurrentCount(RedisEntity redisEntity){
        return redisService.incrementCurrentCount(redisEntity);
    }

    public void sendMessageRedis(int userId,String message){
        //fetch redisEntity
        com.example.RateLimitedSMSGateway.Entity.RedisEntity redisEntity =redisService.queryRedis(userId);
        //not exists ? query from Postgres (CACHE MISS)
        if(redisEntity==null){
            //fetch from postgres
            PostgresEntity postgresEntity=sendMessagePostgresService.fetchUserFromPostgresWithoutLock(userId);
            //last refresh time and ttl
            Instant lastRefreshTime=postgresEntity.getLastRefreshTime();
            Instant currentTime=Instant.now();
            long timeWindow=postgresEntity.getTimeWindow();
            long diffMinutes= Math.abs(Duration.between(lastRefreshTime,currentTime).toMinutes());
            long ttl_minutes;
            int currentCount= postgresEntity.getCurrentCount();
            if(diffMinutes>=timeWindow){
                //update last refresh time
                sendMessagePostgresService.updateLastRefreshRateAndCurrentCount(postgresEntity,currentTime);
                //ttl=timeWindow
                ttl_minutes=timeWindow;
                //update current Count
                currentCount=0;
            }
            else{
                //ttl = timeWindow-diffMinutes
                ttl_minutes=timeWindow-diffMinutes;
            }
            //insert to redis
            redisEntity=redisService.insertToRedis(new RedisEntity(
                    postgresEntity.getUserId(),
                    postgresEntity.getRateLimit(),
                    currentCount,
                    ttl_minutes*60 //convert to seconds
            ));
        }
        //exists
        //check rate limit
        checkRateLimitRedis(redisEntity);
        //update rate limit
        redisEntity=updateCurrentCount(redisEntity);
        //send to kafka
        kafkaConfig.sendToKafka(userId, message);

        // sync currentCount to Postgres (use findById, not FOR UPDATE)
        //BELOW CODE HIT THE DB (WHICH WE DO NOT WANT)
//        PostgresEntity postgresEntity = sendMessagePostgresService.fetchUserFromPostgresWithoutLock(userId);
//        sendMessagePostgresService.updateCurrentRateCountPostgres(postgresEntity);

        //DIRECTLY UPDATING CURRENT COUNT WITHOUT HITTING DB
        sendMessagePostgresService.updateCurrentCountWithoutDBHit(
                userId,
                redisEntity.getCurrentCount()
        );
    }


}
