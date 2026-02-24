package com.example.RateLimitedSMSGateway.Services;

import com.example.RateLimitedSMSGateway.Entity.PostgresEntity;
import com.example.RateLimitedSMSGateway.Repository.PostgresRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;


//UPSERT: INSERT + UPDATE
@Component
public class UpsertDB {

    private final PostgresRepository postgresRepository;

    //constructor injection
    public UpsertDB(PostgresRepository postgresRepository) {
        this.postgresRepository = postgresRepository;
    }

    public PostgresEntity insertUserConfigToDB(int userId, int rateLimit, int timeWindow) {
            Optional<PostgresEntity> entity= postgresRepository.findById(userId); //can throw DataAccessException if postgres is down

            if(entity.isPresent()){
                PostgresEntity currentEntity=entity.get();
                currentEntity.setRateLimit(rateLimit);
                currentEntity.setTimeWindow(timeWindow);
                currentEntity.setCurrentCount(0);
                currentEntity.setLastRefreshTime(Instant.now());
                return postgresRepository.save(currentEntity); //can throw DataAccessException if postgres is down
            }
            else{
                PostgresEntity currentEntity=new PostgresEntity(userId,rateLimit,timeWindow);
                return postgresRepository.save(currentEntity); //can throw DataAccessException if postgres is down
            }
    }
}
