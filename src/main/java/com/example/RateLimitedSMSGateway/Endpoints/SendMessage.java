package com.example.RateLimitedSMSGateway.Endpoints;


import com.example.RateLimitedSMSGateway.DTO.AllRecords;
import com.example.RateLimitedSMSGateway.Services.SendMessagePostgresService;
import com.example.RateLimitedSMSGateway.Services.SendMessageRedisService;
import io.lettuce.core.RedisCommandTimeoutException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SendMessage {

    private final SendMessagePostgresService sendMessagePostgresService;
    private final SendMessageRedisService sendMessageRedisService;
    private final Logger logger = LoggerFactory.getLogger(SendMessage.class);

    //constructor injection
    public SendMessage(SendMessagePostgresService sendMessagePostgresService, SendMessageRedisService sendMessageRedisService) {
        this.sendMessagePostgresService = sendMessagePostgresService;
        this.sendMessageRedisService = sendMessageRedisService;
    }


    @PostMapping("/api/send")
    public ResponseEntity<?> sendMessage(@Valid @RequestBody
    AllRecords.SendRequest sendRequest){
        try{
            sendMessageRedisService.sendMessageRedis(sendRequest.userId(), sendRequest.message());
        }
        catch (RedisConnectionFailureException | RedisCommandTimeoutException | RedisSystemException e) {
            // Redis down — fall back to Postgres path
            logger.error("REDIS DOWN — Falling back to Postgres");
            sendMessagePostgresService.sendMessagePostgres(sendRequest.userId(), sendRequest.message());
        }
            return new ResponseEntity<>("Message Sent Successfully",HttpStatusCode.valueOf(200));
    }
}
