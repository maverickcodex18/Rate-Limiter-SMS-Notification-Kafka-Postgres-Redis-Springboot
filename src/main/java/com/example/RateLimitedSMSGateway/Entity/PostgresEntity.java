package com.example.RateLimitedSMSGateway.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Entity
@NoArgsConstructor
@Table(name = "PostgresEntity")
public class PostgresEntity {

    @Id
    @Column(name = "userId")
    private int userId;

    @Column(name = "rateLimit",nullable = false)
    private int rateLimit;

    @Column(name = "timeWindow",nullable = false)
    private int timeWindow;

    @Column(name = "currentCount",nullable = false)
    private int currentCount;

    @Column(name= "lastRefreshTime",nullable = false)
    private Instant lastRefreshTime;

    //Custom Constructor
    public PostgresEntity(int userId, int rateLimit, int timeWindow){
        this.userId=userId;
        this.rateLimit=rateLimit;
        this.timeWindow=timeWindow;
        this.currentCount=0;
        this.lastRefreshTime=Instant.now();
    }
}
