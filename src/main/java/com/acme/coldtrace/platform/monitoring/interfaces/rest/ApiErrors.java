package com.acme.coldtrace.platform.monitoring.interfaces.rest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> failure(ResponseStatusException e) {
    var problem=ProblemDetail.forStatusAndDetail(e.getStatusCode(),e.getReason()==null?"Request failed":e.getReason());
    if("IDEMPOTENCY_KEY_REUSED".equals(e.getReason())) problem.setProperty("code",e.getReason());
    return ResponseEntity.status(e.getStatusCode()).body(problem);
  }
}
