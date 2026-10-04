package dev.sandbox.timesheet;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.boot.actuate.health.*;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ApiController {
  final TimesheetService service;
  final Telemetry telemetry;

  public ApiController(TimesheetService service, Telemetry telemetry) {
    this.service = service;
    this.telemetry = telemetry;
  }

  @GetMapping("/employees")
  public List<Employee> employees() {
    return service.employees.findAll();
  }

  @PostMapping("/employees")
  public Employee create(@RequestBody Employee e) {
    validate(e);
    return service.employees.save(new Employee(e.name, e.department));
  }

  @PutMapping("/employees/{id}")
  public Employee update(@PathVariable Long id, @RequestBody Employee input) {
    validate(input);
    var e = service.employee(id);
    e.name = input.name;
    e.department = input.department;
    return service.employees.save(e);
  }

  void validate(Employee e) {
    if (e.name == null || e.name.isBlank())
      throw new IllegalArgumentException("Employee name is required");
  }

  @DeleteMapping("/employees/{id}")
  public Map<String, Object> deleteEmployee(@PathVariable Long id) {
    service.employee(id);
    service.entries.deleteAll(service.list(id, null));
    service.employees.deleteById(id);
    return Map.of("deleted", id);
  }

  @GetMapping("/entries")
  public List<TimeEntry> entries(
      @RequestParam(required = false) Long employeeId,
      @RequestParam(required = false) String month) {
    return service.list(employeeId, month);
  }

  @PostMapping("/entries")
  public TimeEntry createEntry(@RequestBody TimeEntry e) {
    return service.save(e, null);
  }

  @PutMapping("/entries/{id}")
  public TimeEntry updateEntry(@PathVariable Long id, @RequestBody TimeEntry e) {
    return service.save(e, id);
  }

  @DeleteMapping("/entries/{id}")
  public Map<String, Object> deleteEntry(@PathVariable Long id) {
    service.entry(id);
    service.entries.deleteById(id);
    return Map.of("deleted", id);
  }

  @GetMapping("/timesheets")
  public Map<String, Object> timesheet(@RequestParam Long employeeId, @RequestParam String month) {
    try {
      return service.timesheet(employeeId, month);
    } catch (java.time.format.DateTimeParseException e) {
      throw new IllegalArgumentException("month must use YYYY-MM", e);
    } catch (DateTimeException e) {
      telemetry.incident(
          "month-boundary",
          "month_boundary",
          "Monthly report failed while computing the last day",
          e,
          "timesheet",
          Map.of("employee_id", employeeId, "month", month));
      throw e;
    }
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    return telemetry.health();
  }

  @Bean
  public HealthIndicator incidentHealthIndicator() {
    return () -> {
      var h = telemetry.health();
      return (h.get("status").equals("healthy") ? Health.up() : Health.down())
          .withDetails(h)
          .build();
    };
  }

  @GetMapping("/scenarios")
  public List<Map<String, String>> scenarios() {
    return List.of(
        Map.of(
            "id",
            "monthly-total",
            "name",
            "Decimal hours drift",
            "description",
            "Compare fractional hour entries with the monthly report."),
        Map.of(
            "id",
            "duplicate-submission",
            "name",
            "Submission retry",
            "description",
            "Retry a submission and inspect persisted entries."),
        Map.of(
            "id",
            "month-boundary",
            "name",
            "February report",
            "description",
            "Generate a report for a short month."),
        Map.of(
            "id",
            "baseline",
            "name",
            "Healthy baseline",
            "description",
            "Submit whole hours and generate a January report."));
  }

  @PostMapping("/scenarios/reset")
  public Map<String, Object> reset() {
    service.entries.deleteAll();
    service.employees.deleteAll();
    service.employees.save(new Employee("Alex Morgan", "Engineering"));
    telemetry.reset();
    telemetry.scenario("reset");
    telemetry.emit("info", "Employees, entries and incident state reset", Map.of(), null);
    return health();
  }

  @PostMapping("/scenarios/{id}/run")
  public Map<String, Object> run(@PathVariable String id) {
    if (scenarios().stream().noneMatch(x -> x.get("id").equals(id)))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario not found");
    telemetry.scenario(id);
    var e = service.employees.save(new Employee("Scenario " + id, "Sandbox"));
    String month = id.equals("month-boundary") ? "2026-02" : "2026-01";
    var details = new LinkedHashMap<String, Object>();
    String status = "healthy", message = "Workload completed without a consistency failure";
    telemetry.emit("info", "Scenario workload started", Map.of("employee_id", e.id), null);
    if (id.equals("duplicate-submission")) {
      String key = UUID.randomUUID().toString();
      var input =
          new TimeEntry(
              e.id, LocalDate.parse("2026-01-12"), new BigDecimal("8"), "Retried submission", key);
      service.save(input, null);
      service.save(input, null);
      details.put("entries", service.list(e.id, month));
      details.put("submissionId", key);
      long actualCount =
          service.list(e.id, month).stream().filter(row -> key.equals(row.submissionId)).count();
      details.put("actualSubmissionCount", actualCount);
      details.put("expectedSubmissionCount", 1);
      if (actualCount != 1) {
        status = "degraded";
        message = "Retry persisted duplicate entries";
      }
    } else {
      service.save(
          new TimeEntry(
              e.id,
              YearMonth.parse(month).atDay(12),
              new BigDecimal(id.equals("monthly-total") ? "7.50" : "8"),
              "Scenario work",
              UUID.randomUUID().toString()),
          null);
      if (id.equals("monthly-total"))
        service.save(
            new TimeEntry(
                e.id,
                LocalDate.parse("2026-01-13"),
                new BigDecimal("6.25"),
                "Review work",
                UUID.randomUUID().toString()),
            null);
      try {
        details.putAll(timesheet(e.id, month));
        if (id.equals("monthly-total")) {
          BigDecimal expected =
              service.list(e.id, month).stream()
                  .map(row -> row.hours)
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
          details.put("expectedHours", expected);
          if (((BigDecimal) details.get("totalHours")).compareTo(expected) != 0) {
            status = "degraded";
            message = "Monthly total differs from persisted entry sum";
          }
        }
      } catch (DateTimeException ex) {
        status = "unhealthy";
        message = "Monthly report failed at month boundary";
        details.put("error", ex.getMessage());
        details.put("exception", ex.getClass().getName());
      }
    }
    return Map.of(
        "scenarioId",
        id,
        "status",
        status,
        "message",
        message,
        "employeeId",
        e.id,
        "month",
        month,
        "details",
        details);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> error(Exception e) {
    int status =
        e instanceof ResponseStatusException r
            ? r.getStatusCode().value()
            : e instanceof IllegalArgumentException
                    || e instanceof java.time.format.DateTimeParseException
                    || e
                        instanceof
                        org.springframework.http.converter.HttpMessageNotReadableException
                    || e
                        instanceof
                        org.springframework.web.bind.MissingServletRequestParameterException
                    || e
                        instanceof
                        org.springframework.web.method.annotation
                            .MethodArgumentTypeMismatchException
                ? 400
                : 500;
    String message =
        e instanceof ResponseStatusException r
            ? r.getReason()
            : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    telemetry.emit(
        status >= 500 ? "error" : "warn", message, Map.of("http.status_code", status), e);
    return ResponseEntity.status(status).body(Map.of("status", status, "error", message));
  }
}
