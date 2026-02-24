package com.example.RateLimitedSMSGateway.Endpoints;

import com.example.RateLimitedSMSGateway.DTO.AllRecords;
import com.example.RateLimitedSMSGateway.Entity.PostgresEntity;
import com.example.RateLimitedSMSGateway.Services.RedisService;
import com.example.RateLimitedSMSGateway.Services.UpsertDB;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CreateUserConfig {

    private final UpsertDB upsertDB;
    private final RedisService redisService;
    private final Logger logger = LoggerFactory.getLogger(CreateUserConfig.class);


    //constructor injection
    public CreateUserConfig(UpsertDB upsertDB, RedisService redisService) {
        this.upsertDB = upsertDB;
        this.redisService = redisService;
    }

    @PostMapping("/api/config")
    public ResponseEntity<?> addUser(@Valid @RequestBody AllRecords.RateLimitConfigRequest userData) {

            PostgresEntity createdEntity=upsertDB.insertUserConfigToDB(userData.userId(),userData.rateLimit(), userData.timeWindow());
            AllRecords.RespondCreateUserConfig response= new AllRecords.RespondCreateUserConfig(
                createdEntity.getUserId(),
                createdEntity.getRateLimit(),
                createdEntity.getTimeWindow(),
                createdEntity.getCurrentCount(),
                createdEntity.getLastRefreshTime()
            );
            //invalidate redis cache (delete cache with id=userId)
        try{
            redisService.deleteUser(userData.userId());
        } catch (Exception e) {
            //Redis Down
            logger.error("REDIS DOWN — Falling back to Postgres");
        }

            return new ResponseEntity<>(response, HttpStatus.OK); //respond with 200

    }
}
