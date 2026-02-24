package com.example.RateLimitedSMSGateway.CustomExceptions;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionRepository {
    @ExceptionHandler(CustomExceptionTemplate.class)
    public ResponseEntity<?> CustomExceptionHandler(CustomExceptionTemplate exc){
        return new ResponseEntity<>(exc.getMessage(), HttpStatusCode.valueOf(exc.getStatusCode()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> DBExceptionHandler(DataAccessException exc){
        return new ResponseEntity<>("Postgres Down : Service Unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> DBExceptionHandler(MethodArgumentNotValidException exc){
        return new ResponseEntity<>("Missing/Invalid Arguments", HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> ExceptionHandler(Exception exc){
        return new ResponseEntity<>("Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
