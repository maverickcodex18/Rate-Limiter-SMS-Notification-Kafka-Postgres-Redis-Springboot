package com.example.RateLimitedSMSGateway.Repository;

import com.example.RateLimitedSMSGateway.Entity.PostgresEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PostgresRepository extends JpaRepository<PostgresEntity, Integer> {
    //creating LOCK
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PostgresEntity r WHERE r.userId = :userId")
    Optional<PostgresEntity> findByUserIdForUpdate(@Param("userId") int userId);

    //DIRECTLY UPDATING CURRENT COUNT WITHOUT HITTING DB
    @Modifying
    @Query("UPDATE PostgresEntity r SET r.currentCount=:currentCount WHERE r.userId=:userId")
    void updateCurrentCountWithoutDBHit(@Param("userId")int userId,@Param("currentCount")int currentCount);
}
