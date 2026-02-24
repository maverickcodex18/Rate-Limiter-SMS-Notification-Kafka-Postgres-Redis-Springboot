package com.example.RateLimitedSMSGateway.Services;


import com.example.RateLimitedSMSGateway.CustomExceptions.RateLimitExceededException;
import com.example.RateLimitedSMSGateway.CustomExceptions.UserNotFoundException;
import com.example.RateLimitedSMSGateway.Entity.PostgresEntity;
import com.example.RateLimitedSMSGateway.Repository.PostgresRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


@Component
public class SendMessagePostgresService {

    private final PostgresRepository postgresRepository;
    private final KafkaConfig kafkaConfig;


    //constructor injection
    public SendMessagePostgresService(PostgresRepository postgresRepository, KafkaConfig kafkaConfig) {
        this.postgresRepository = postgresRepository;
        this.kafkaConfig=kafkaConfig;
    }

    public PostgresEntity fetchUserFromPostgresWithoutLock(int userId){
        Optional<PostgresEntity> entity= postgresRepository.findById(userId);
        if(entity.isPresent()) return entity.get();
        throw new UserNotFoundException();
    }


    private void checkRateLimitAndUpdateRefreshTimePostgres(PostgresEntity entity){
        //update : last refresh time  if required
                int currentCount=entity.getCurrentCount();
                int rateLimit=entity.getRateLimit();
                Instant lastRefreshTime=entity.getLastRefreshTime();

                //condition 1: check current time - last refresh time >=timeWindow
                Instant currentTime=Instant.now();
                long diffMinutes= Math.abs(Duration.between(lastRefreshTime,currentTime).toMinutes());
                if(diffMinutes>=entity.getTimeWindow()){
                    //update last refresh time to now and rate limit to 0
                    entity.setCurrentCount(0);
                    entity.setLastRefreshTime(currentTime);
                    entity= postgresRepository.save(entity);
                }
                //condition 2: check whether rate limit is allowed or not
                else if(currentCount>=rateLimit){
                    throw new RateLimitExceededException();
                }
    }

    public void updateCurrentRateCountPostgres(PostgresEntity entity){
            entity.setCurrentCount(entity.getCurrentCount()+1);
            entity= postgresRepository.save(entity);
    }

    public void updateLastRefreshRateAndCurrentCount(PostgresEntity postgresEntity,Instant currentTime){
        postgresEntity.setLastRefreshTime(currentTime);
        postgresEntity.setCurrentCount(0);
        postgresRepository.save(postgresEntity);
    }

    @Transactional
    public void updateCurrentCountWithoutDBHit(int userId,int currentCount){
        postgresRepository.updateCurrentCountWithoutDBHit(userId,currentCount);
    }

    @Transactional //to implement LOCK
    public void sendMessagePostgres(int userId, String message){

            //fetching user
            Optional<PostgresEntity> entity= postgresRepository.findByUserIdForUpdate(userId);
            if(entity.isPresent()) {
                PostgresEntity currentEntity=entity.get();
                //rate limit for current user
                checkRateLimitAndUpdateRefreshTimePostgres(currentEntity);

                //rate limit exceeded will automatically throw the error
                //send to kafka
                kafkaConfig.sendToKafka(userId, message);

                //if kafka does not throw exception => we can implement current Rate count of user ID
                updateCurrentRateCountPostgres(currentEntity);
            }
            else throw new UserNotFoundException();
    }

}
