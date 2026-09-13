package com.fooddelivery.reviews.web;

import com.fooddelivery.common.dto.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.validation.ConstraintViolationException;
import com.fooddelivery.reviews.exception.ExternalServiceUnavailableException;
import com.fooddelivery.reviews.exception.ReviewEventSerializationException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReviewsGlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        String message = (body != null) ? body.toString() : ex.getMessage();
        if (message == null) {
            message = "An error occurred";
        }
        log.warn("Handled internal exception: {} - {}", ex.getClass().getSimpleName(), message);
        return new ResponseEntity<>(ApiResponse.error(message), headers, statusCode);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, 
                                                                  HttpHeaders headers, 
                                                                  HttpStatusCode status, 
                                                                  WebRequest request) {
        String errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        
        log.warn("Validation failed: {}", errors);
        return new ResponseEntity<>(ApiResponse.error("Validation failed: " + errors), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch: {}", ex.getMessage());
        return new ResponseEntity<>(ApiResponse.error("Invalid parameter type: " + ex.getName()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMessage());
        return new ResponseEntity<>(ApiResponse.error("Validation failed: " + ex.getMessage()), HttpStatus.BAD_REQUEST);
    }

    /**
     * A refusal the customer can act on. The status comes from the reason itself rather than being
     * re-derived here, and the reason's name goes out as {@code errorCode} so the UI can say
     * "the 14-day window has closed" instead of "something went wrong".
     */
    @ExceptionHandler(com.fooddelivery.reviews.exception.ReviewNotAllowedException.class)
    public ResponseEntity<ApiResponse<Void>> handleReviewNotAllowed(
            com.fooddelivery.reviews.exception.ReviewNotAllowedException ex) {
        log.info("Review refused ({}): {}", ex.getReason(), ex.getMessage());
        return new ResponseEntity<>(
                ApiResponse.error(ex.getMessage(), ex.getReason().name()),
                ex.getReason().getStatus());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFoundException(EntityNotFoundException ex) {
        log.warn("Entity not found: {}", ex.getMessage());
        return new ResponseEntity<>(ApiResponse.error(ex.getMessage()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic locking failure (Conflict): {}", ex.getMessage());
        return new ResponseEntity<>(ApiResponse.error("The resource was modified by another transaction. Please retry."), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(org.springframework.dao.DataIntegrityViolationException ex) {
        log.warn("Data integrity violation (Conflict): {}", ex.getMessage());
        return new ResponseEntity<>(ApiResponse.error("The request conflicts with existing data (e.g., duplicate entry)."), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return new ResponseEntity<>(ApiResponse.error(ex.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ExternalServiceUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleExternalServiceUnavailable(ExternalServiceUnavailableException ex) {
        log.error("External service unavailable: {}", ex.getMessage());
        return new ResponseEntity<>(ApiResponse.error("Service unavailable: " + ex.getMessage()), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(ReviewEventSerializationException.class)
    public ResponseEntity<ApiResponse<Void>> handleReviewEventSerialization(ReviewEventSerializationException ex) {
        log.error("Serialization failed: {}", ex.getMessage(), ex);
        return new ResponseEntity<>(ApiResponse.error("Internal server error during event serialization"), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException ex) {
        log.warn("Illegal state (Bad Request): {}", ex.getMessage());
        return new ResponseEntity<>(ApiResponse.error(ex.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(feign.FeignException.class)
    public ResponseEntity<ApiResponse<Void>> handleFeignException(feign.FeignException ex) {
        log.warn("Upstream service error (Feign): status={}, message={}", ex.status(), ex.getMessage());
        HttpStatus status = HttpStatus.resolve(ex.status());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return new ResponseEntity<>(ApiResponse.error(ex.getMessage()), status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAllOtherExceptions(Exception ex) {
        log.error("Internal Server Error: ", ex);
        return new ResponseEntity<>(ApiResponse.error("An unexpected error occurred."), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
