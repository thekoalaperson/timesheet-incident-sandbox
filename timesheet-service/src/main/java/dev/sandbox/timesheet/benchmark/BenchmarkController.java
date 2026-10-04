package dev.sandbox.timesheet.benchmark;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {
  private final BenchmarkService service;

  public BenchmarkController(BenchmarkService service) {
    this.service = service;
  }

  @GetMapping("/cases")
  public List<Map<String, Object>> cases() {
    return service.cases();
  }

  @PostMapping("/runs")
  public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, Object> input) {
    return ResponseEntity.accepted().body(service.start(input));
  }

  @GetMapping("/runs")
  public List<Map<String, Object>> runs() {
    return service.list();
  }

  @GetMapping("/runs/{id}")
  public Map<String, Object> run(@PathVariable String id) {
    return service.summary(service.require(id));
  }

  @GetMapping("/runs/{id}/context")
  public Map<String, Object> context(@PathVariable String id) {
    return service.context(id);
  }
}
