package dev.sandbox.timesheet;

import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {
  private final WorkflowService service;

  public WorkflowController(WorkflowService service) {
    this.service = service;
  }

  @GetMapping("/teams")
  public List<WorkflowService.Team> teams() {
    return service.teams();
  }

  @PostMapping("/teams")
  public WorkflowService.Team team(@RequestBody Map<String, Object> input) {
    return service.createTeam(input);
  }

  @GetMapping("/projects")
  public List<WorkflowService.Project> projects() {
    return service.projects();
  }

  @PostMapping("/projects")
  public WorkflowService.Project project(@RequestBody Map<String, Object> input) {
    return service.createProject(input);
  }

  @GetMapping("/entries")
  public List<WorkflowService.WorkRecord> entries() {
    return service.records();
  }

  @PostMapping("/entries")
  public WorkflowService.WorkRecord entry(@RequestBody Map<String, Object> input) {
    return service.saveRecord(input, null);
  }

  @PutMapping("/entries/{id}")
  public WorkflowService.WorkRecord edit(
      @PathVariable Long id, @RequestBody Map<String, Object> input) {
    return service.saveRecord(input, id);
  }

  @GetMapping("/timesheets")
  public List<WorkflowService.Sheet> sheets() {
    return service.sheets();
  }

  @PostMapping("/timesheets/submit")
  public WorkflowService.Sheet submit(@RequestBody Map<String, Object> input) {
    return service.submit(input);
  }

  @PostMapping("/timesheets/{id}/approve")
  public WorkflowService.Sheet approve(@PathVariable Long id) {
    return service.approve(id);
  }

  @GetMapping("/ledger")
  public List<WorkflowService.Charge> ledger() {
    return service.ledger();
  }

  @GetMapping("/audit")
  public List<WorkflowService.Audit> audit() {
    return service.audit();
  }

  @GetMapping("/periods")
  public List<WorkflowService.Period> periods() {
    return service.periods();
  }

  @PostMapping("/periods/close")
  public WorkflowService.Period close(@RequestBody Map<String, Object> input) {
    return service.close(input);
  }

  @GetMapping("/invoices/preview")
  public Map<String, Object> invoice(@RequestParam Long projectId, @RequestParam String month) {
    return service.invoice(projectId, month);
  }

  @GetMapping("/reports/team")
  public Map<String, Object> teamReport(@RequestParam Long teamId, @RequestParam String month) {
    return service.teamReport(teamId, month);
  }

  @GetMapping("/reports/overtime")
  public Map<String, Object> overtime(
      @RequestParam Long employeeId, @RequestParam LocalDate weekStart) {
    return service.overtime(employeeId, weekStart);
  }

  @GetMapping("/exports")
  public List<WorkflowService.ExportJob> exports() {
    return service.exports();
  }

  @PostMapping("/exports")
  public WorkflowService.ExportJob export(@RequestBody Map<String, Object> input) {
    return service.export(input);
  }

  @GetMapping("/exports/{id}")
  public WorkflowService.ExportJob exportJob(@PathVariable String id) {
    return service.exportJob(id);
  }

  @PostMapping("/deliveries")
  public Map<String, Object> deliver(@RequestBody Map<String, Object> input) {
    return service.deliver(input);
  }

  @GetMapping("/dependency/ack")
  public Map<String, Object> ack(@RequestParam(defaultValue = "0") int delayMs) {
    return service.acknowledgement(delayMs);
  }

  @RestControllerAdvice
  public static class WorkflowErrors {
    private final Telemetry telemetry;

    public WorkflowErrors(Telemetry telemetry) {
      this.telemetry = telemetry;
    }

    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> errors(Exception error) {
      int status =
          error instanceof org.springframework.web.server.ResponseStatusException response
              ? response.getStatusCode().value()
              : error instanceof IllegalArgumentException
                      || error instanceof java.time.DateTimeException
                      || error
                          instanceof
                          org.springframework.http.converter.HttpMessageNotReadableException
                      || error
                          instanceof
                          org.springframework.web.bind.MissingServletRequestParameterException
                      || error
                          instanceof
                          org.springframework.web.method.annotation
                              .MethodArgumentTypeMismatchException
                  ? 400
                  : 500;
      String message =
          error instanceof org.springframework.web.server.ResponseStatusException response
              ? response.getReason()
              : Objects.toString(error.getMessage(), error.getClass().getSimpleName());
      telemetry.emit(
          status >= 500 ? "error" : "warn",
          message,
          Map.of(
              "http.status_code",
              status,
              "code.file",
              "src/main/java/dev/sandbox/timesheet/WorkflowController.java",
              "code.function",
              "errors"),
          error);
      return org.springframework.http.ResponseEntity.status(status)
          .body(Map.of("status", status, "error", message));
    }
  }
}
