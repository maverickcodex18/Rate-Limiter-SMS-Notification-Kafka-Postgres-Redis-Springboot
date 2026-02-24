package com.example.RateLimitedSMSGateway.Services;

import com.example.RateLimitedSMSGateway.CustomExceptions.KafkaPublishException;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;

@Component
public class KafkaConfig {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Logger logger= LoggerFactory.getLogger(KafkaConfig.class);

    //constructor injection
    public KafkaConfig(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Bean
    public NewTopic topic() {
        return TopicBuilder.name("sms-outbound")
                .partitions(4)
                .replicas(1)
                .build();
    }

    public void sendToKafka(int userId,String message){
        try{
            kafkaTemplate.send("sms-outbound",userId+": "+message).get(); //blocks until sending is complete (MAKES SYNCHRONOUS)
        } catch (Exception e) {
            throw new KafkaPublishException();
        }
    }

    @KafkaListener(id = "myId", topics = "sms-outbound")
    public void listen(String in) {
        try{
            logger.info("SMS Sent: "+in);
        } catch (Exception e) {
            logger.info(e.getMessage());
        }
    }
}
