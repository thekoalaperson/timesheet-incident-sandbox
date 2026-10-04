package dev.sandbox.timesheet.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sandbox.timesheet.*;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BenchmarkService {
  public static class Run {
    public String runId;
    public String caseId;
    public int seed;
    public int noise;
    public volatile String state = "queued";
    public String outcome = "healthy";
    public Instant startedAt = Instant.now();
    public Instant completedAt;
    public List<String> requestIds = new CopyOnWriteArrayList<>();
    public List<Map<String, Object>> correlationIds = new CopyOnWriteArrayList<>();
    public Map<String, Object> fixture = new ConcurrentHashMap<>();
    public Map<String, Object> observations = new ConcurrentHashMap<>();
  }

  private record Response(int status, Map<String, Object> body) {}

  private final WorkflowService workflow;
  private final Telemetry telemetry;
  private final ObjectMapper mapper;
  @PersistenceContext private EntityManager snapshots;
  private final TransactionTemplate snapshotTransaction;
  private volatile int port;
  private final ExecutorService workers =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(32),
          new ThreadPoolExecutor.AbortPolicy());
  private final ExecutorService approvals =
      new ThreadPoolExecutor(
          8,
          8,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(32),
          new ThreadPoolExecutor.AbortPolicy());
  private final java.util.concurrent.locks.ReentrantLock reportFixtureLock =
      new java.util.concurrent.locks.ReentrantLock();
  private final Map<String, Run> runs = new ConcurrentHashMap<>();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public BenchmarkService(
      WorkflowService workflow,
      Telemetry telemetry,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager,
      @Value("${server.port:8080}") int port) {
    this.workflow = workflow;
    this.telemetry = telemetry;
    this.mapper = mapper;
    this.snapshotTransaction = new TransactionTemplate(transactionManager);
    this.snapshotTransaction.setReadOnly(true);
    this.port = port;
  }

  private Map<String, Object> metadata(
      String id, String title, int level, List<String> tags, String description) {
    return Map.of(
        "id", id, "title", title, "level", level, "tags", tags, "description", description);
  }

  public List<Map<String, Object>> cases() {
    return List.of(
        metadata(
            "case-101",
            "Overtime across a year boundary",
            1,
            List.of("reporting", "calendar"),
            "A weekly overtime report is compared with approved work spanning December and January."),
        metadata(
            "case-201",
            "Invoice and approval reconciliation",
            2,
            List.of("billing", "approvals"),
            "Compare a project invoice preview with its approved and pending work."),
        metadata(
            "case-202",
            "Team report isolation",
            2,
            List.of("reporting", "teams"),
            "Two teams request reports for the same reporting period."),
        metadata(
            "case-203",
            "Report refresh after approval",
            3,
            List.of("reporting", "approvals"),
            "A team report is requested before and after additional work is approved."),
        metadata(
            "case-301",
            "Concurrent approval reconciliation",
            3,
            List.of("concurrency", "budgets"),
            "Two approval requests arrive together; inspect the resulting budget ledger."),
        metadata(
            "case-302",
            "Concurrent project budget limit",
            3,
            List.of("concurrency", "budgets"),
            "Different timesheets are approved concurrently against a shared project budget."),
        metadata(
            "case-401",
            "Closed period integrity",
            3,
            List.of("periods", "editing"),
            "An edit request follows approval and period close."),
        metadata(
            "case-402",
            "Late-posted work export",
            4,
            List.of("exports", "asynchronous"),
            "Approved work posted after its work period is exported for reconciliation."),
        metadata(
            "case-501",
            "Billing and export reconciliation",
            4,
            List.of("billing", "exports", "multi-signal"),
            "Reconcile approval records with both the invoice preview and exported report."),
        metadata(
            "case-901",
            "Delivery retry recovery",
            2,
            List.of("deliveries", "dependencies"),
            "A delivery acknowledgement times out, then a retry completes."),
        metadata(
            "case-902",
            "Input rejection and recovery",
            1,
            List.of("entries", "validation"),
            "Reject an invalid time entry, then process a valid entry."),
        metadata(
            "case-903",
            "Full reporting workflow",
            1,
            List.of("approvals", "workflow"),
            "Create, submit, approve, close, invoice, and export a normal reporting period."));
  }

  public Map<String, Object> start(Map<String, Object> input) {
    String caseId = Objects.toString(input.get("caseId"), "");
    if (cases().stream().noneMatch(c -> c.get("id").equals(caseId)))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Benchmark case not found");
    int seed = integer(input.getOrDefault("seed", 1), "seed"),
        noise = integer(input.getOrDefault("noise", 0), "noise");
    if (noise < 0 || noise > 100) throw new IllegalArgumentException("noise must be 0..100");
    if (runs.size() >= 200) {
      var oldest =
          runs.values().stream()
              .filter(r -> r.completedAt != null)
              .min(Comparator.comparing(r -> r.startedAt));
      oldest.ifPresent(
          r -> {
            runs.remove(r.runId);
            workflow.unregisterFixtureClock(r.runId);
          });
    }
    if (runs.size() >= 200)
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Benchmark run capacity reached");
    Run run = new Run();
    run.runId = UUID.randomUUID().toString();
    run.caseId = caseId;
    run.seed = seed;
    run.noise = noise;
    int year = 2030 + Math.floorMod(seed, 10), month = 1 + Math.floorMod(seed / 10, 12);
    YearMonth period = YearMonth.of(year, month);
    if (caseId.equals("case-101")) {
      int[] followingYears = {2025, 2026, 2027, 2028, 2030, 2031};
      period = YearMonth.of(followingYears[Math.floorMod(seed, followingYears.length)] - 1, 12);
    }
    Instant asOf = period.atDay(20).atTime(12, 0).toInstant(ZoneOffset.UTC);
    if (caseId.equals("case-402") || caseId.equals("case-501"))
      asOf = period.plusMonths(1).atDay(5).atTime(12, 0).toInstant(ZoneOffset.UTC);
    BigDecimal approvedHours =
        BigDecimal.valueOf(5 + Math.floorMod(seed, 7))
            .add(new BigDecimal("0.25").multiply(BigDecimal.valueOf(Math.floorMod(seed, 3))));
    BigDecimal extraHours =
        BigDecimal.valueOf(2 + Math.floorMod(seed, 4))
            .add(new BigDecimal("0.50").multiply(BigDecimal.valueOf(Math.floorMod(seed, 3))));
    run.fixture.put("approvedHours", approvedHours);
    run.fixture.put("extraHours", extraHours);
    run.fixture.put("peerHours", approvedHours.add(extraHours).add(new BigDecimal("4")));
    run.fixture.put(
        "dailyHours",
        BigDecimal.valueOf(14 + Math.floorMod(seed, 5))
            .add(new BigDecimal("0.25").multiply(BigDecimal.valueOf(Math.floorMod(seed, 4)))));
    run.fixture.put(
        "hourlyRate",
        BigDecimal.valueOf(75 + 15 * Math.floorMod(seed, 8))
            .add(new BigDecimal("0.50").multiply(BigDecimal.valueOf(Math.floorMod(seed, 2)))));
    run.fixture.put(
        "budgetHours",
        caseId.equals("case-302")
            ? approvedHours
                .multiply(new BigDecimal("1.50"))
                .setScale(2, java.math.RoundingMode.HALF_UP)
            : BigDecimal.valueOf(200 + 25 * Math.floorMod(seed, 10)));
    run.fixture.put("month", period.toString());
    run.fixture.put("asOf", asOf.toString());
    run.fixture.put("teamIds", new CopyOnWriteArrayList<Long>());
    run.fixture.put("projectIds", new CopyOnWriteArrayList<Long>());
    run.fixture.put("employeeIds", new CopyOnWriteArrayList<Long>());
    runs.put(run.runId, run);
    workflow.registerFixtureClock(run.runId, asOf);
    var context = telemetry.capture();
    context.put("run_id", run.runId);
    context.put("case_id", caseId);
    try {
      workers.submit(() -> execute(run, context));
    } catch (RejectedExecutionException e) {
      runs.remove(run.runId);
      workflow.unregisterFixtureClock(run.runId);
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Benchmark workers are busy");
    }
    return summary(run);
  }

  private static int integer(Object value, String name) {
    try {
      return new BigDecimal(value.toString()).intValueExact();
    } catch (Exception e) {
      throw new IllegalArgumentException(name + " must be an integer");
    }
  }

  public Run require(String id) {
    var run = runs.get(id);
    if (run == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Benchmark run not found");
    return run;
  }

  public Map<String, Object> summary(Run run) {
    var out = new LinkedHashMap<String, Object>();
    out.put("runId", run.runId);
    out.put("caseId", run.caseId);
    out.put("seed", run.seed);
    out.put("noise", run.noise);
    out.put("state", run.state);
    out.put("outcome", run.outcome);
    out.put("startedAt", run.startedAt.toString());
    if (run.completedAt != null) out.put("completedAt", run.completedAt.toString());
    out.put("service", "timesheet-service");
    out.put("requestIds", List.copyOf(run.requestIds));
    out.put("artifactRefs", List.of("/api/benchmark/runs/" + run.runId + "/context"));
    return out;
  }

  public List<Map<String, Object>> list() {
    return runs.values().stream()
        .sorted(Comparator.comparing((Run r) -> r.startedAt).reversed())
        .map(this::summary)
        .toList();
  }

  public Map<String, Object> context(String id) {
    Run run = require(id);
    return snapshotTransaction.execute(
        status -> {
          var out = new LinkedHashMap<String, Object>();
          out.putAll(summary(run));
          out.put("fixture", run.fixture);
          out.put("observations", run.observations);
          out.put("teams", snapshot(WorkflowService.Team.class, id));
          out.put("projects", snapshot(WorkflowService.Project.class, id));
          out.put("entries", snapshot(WorkflowService.WorkRecord.class, id));
          out.put("timesheets", snapshot(WorkflowService.Sheet.class, id));
          out.put("ledger", snapshot(WorkflowService.Charge.class, id));
          out.put("periods", snapshot(WorkflowService.Period.class, id));
          out.put("audit", snapshot(WorkflowService.Audit.class, id));
          out.put("jobs", workflow.exports().stream().filter(e -> id.equals(e.runId)).toList());
          out.put("correlationIds", List.copyOf(run.correlationIds));
          return out;
        });
  }

  private <T> List<T> snapshot(Class<T> entityType, String runId) {
    String entityName = snapshots.getMetamodel().entity(entityType).getName();
    return snapshots
        .createQuery(
            "select e from " + entityName + " e where e.runId = :runId order by e.id", entityType)
        .setParameter("runId", runId)
        .getResultList();
  }

  private List<WorkflowService.WorkRecord> records(Run run) {
    return workflow.records().stream().filter(e -> run.runId.equals(e.runId)).toList();
  }

  private Response call(Run run, String step, String method, String path, Object body) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(15))
              .header("Content-Type", "application/json");
      if (run != null)
        request
            .header("X-Benchmark-run-id", run.runId)
            .header("X-Benchmark-case-id", run.caseId)
            .header("X-Benchmark-step", step);
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
      var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (run != null) {
        String requestId = response.headers().firstValue("X-Request-Id").orElse("");
        String traceId = response.headers().firstValue("X-Trace-Id").orElse("");
        run.requestIds.add(requestId);
        run.correlationIds.add(
            Map.of(
                "requestId",
                requestId,
                "traceId",
                traceId,
                "step",
                step,
                "method",
                method,
                "path",
                path,
                "status",
                response.statusCode()));
      }
      Map<String, Object> value;
      if (response.body().startsWith("["))
        value =
            Map.of(
                "items",
                mapper.readValue(
                    response.body(), new TypeReference<List<Map<String, Object>>>() {}));
      else value = mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
      return new Response(response.statusCode(), value);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("Workflow request failed at " + step, e);
    }
  }

  private Map<String, Object> success(
      Run run, String step, String method, String path, Object body) {
    var response = call(run, step, method, path, body);
    if (response.status >= 400)
      throw new IllegalStateException(
          "Workflow request "
              + step
              + " returned "
              + response.status
              + ": "
              + response.body.get("error"));
    return response.body;
  }

  private long id(Map<String, Object> value) {
    return ((Number) value.get("id")).longValue();
  }

  @SuppressWarnings("unchecked")
  private void fixtureId(Run run, String field, long id) {
    ((List<Long>) run.fixture.get(field)).add(id);
  }

  private long team(Run run, String label) {
    long id =
        id(
            success(
                run,
                "create-team",
                "POST",
                "/api/workflow/teams",
                Map.of("name", label + " " + run.runId.substring(0, 8))));
    fixtureId(run, "teamIds", id);
    return id;
  }

  private long project(Run run, long team, String label) {
    long id =
        id(
            success(
                run,
                "create-project",
                "POST",
                "/api/workflow/projects",
                Map.of(
                    "teamId",
                    team,
                    "name",
                    label,
                    "budgetHours",
                    run.fixture.get("budgetHours"),
                    "hourlyRate",
                    run.fixture.get("hourlyRate"))));
    fixtureId(run, "projectIds", id);
    return id;
  }

  private long employee(Run run, String label) {
    long id =
        id(
            success(
                run,
                "create-employee",
                "POST",
                "/api/employees",
                Map.of(
                    "name", label + " " + run.runId.substring(0, 8), "department", "Benchmark")));
    fixtureId(run, "employeeIds", id);
    return id;
  }

  private Map<String, Object> entryInput(long project, long employee, String date, Object hours) {
    return Map.of(
        "projectId",
        project,
        "employeeId",
        employee,
        "workDate",
        date,
        "hours",
        hours,
        "description",
        "Work recorded for reporting");
  }

  private long entry(Run run, long project, long employee, String date, Object hours) {
    return id(
        success(
            run,
            "record-work",
            "POST",
            "/api/workflow/entries",
            entryInput(project, employee, date, hours)));
  }

  private long submit(Run run, long project, long employee, String month) {
    return id(
        success(
            run,
            "submit-timesheet",
            "POST",
            "/api/workflow/timesheets/submit",
            Map.of("projectId", project, "employeeId", employee, "month", month)));
  }

  private void approve(Run run, long sheet) {
    success(
        run,
        "approve-timesheet",
        "POST",
        "/api/workflow/timesheets/" + sheet + "/approve",
        Map.of());
  }

  private Map<String, Object> export(Run run, long team, String month) {
    var job =
        success(
            run,
            "queue-export",
            "POST",
            "/api/workflow/exports",
            Map.of("teamId", team, "month", month));
    String id = job.get("id").toString();
    for (int attempt = 0; attempt < 100; attempt++) {
      job = success(run, "poll-export", "GET", "/api/workflow/exports/" + id, null);
      if (job.get("state").equals("completed") || job.get("state").equals("failed")) return job;
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Export wait interrupted", e);
      }
    }
    throw new IllegalStateException("Export did not finish");
  }

  private void symptom(
      Run run, String kind, String message, String function, Map<String, Object> details) {
    run.outcome = "degraded";
    var attrs = new LinkedHashMap<String, Object>(details);
    attrs.put("run_id", run.runId);
    attrs.put("case_id", run.caseId);
    attrs.put("code.file", "src/main/java/dev/sandbox/timesheet/WorkflowService.java");
    telemetry.incident(run.caseId, kind, message, null, function, attrs);
  }

  private BigDecimal sum(Run run, Predicate<WorkflowService.WorkRecord> filter) {
    return records(run).stream()
        .filter(filter)
        .map(e -> e.hours)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal amount(Object value) {
    return new BigDecimal(value.toString());
  }

  private void reconcileInvoice(Run run, long project, String month) {
    var invoice =
        success(
            run,
            "invoice-preview",
            "GET",
            "/api/workflow/invoices/preview?projectId=" + project + "&month=" + month,
            null);
    run.observations.put("invoice", invoice);
    BigDecimal approved =
        sum(
            run,
            r ->
                r.projectId == project
                    && "APPROVED".equals(r.status)
                    && YearMonth.from(r.workDate).toString().equals(month));
    if (amount(invoice.get("hours")).compareTo(approved) != 0)
      symptom(
          run,
          "billing_reconciliation",
          "Invoice hours disagree with approved work",
          "invoice",
          Map.of(
              "project_id",
              project,
              "month",
              month,
              "invoice_hours",
              invoice.get("hours"),
              "approved_hours",
              approved));
  }

  @SuppressWarnings("unchecked")
  private void reconcileExport(Run run, long team, String month) {
    var result = export(run, team, month);
    run.observations.put("export", result);
    Set<Long> approved = new HashSet<>();
    records(run).stream()
        .filter(
            r ->
                r.teamId == team
                    && "APPROVED".equals(r.status)
                    && YearMonth.from(r.workDate).toString().equals(month))
        .forEach(r -> approved.add(r.id));
    Set<Long> delivered = new HashSet<>();
    for (var row : (List<Map<String, Object>>) result.get("rows"))
      delivered.add(((Number) row.get("id")).longValue());
    if (!approved.equals(delivered) || !result.get("state").equals("completed"))
      symptom(
          run,
          "export_reconciliation",
          "Exported rows disagree with approved period work",
          "executeExport",
          Map.of(
              "team_id",
              team,
              "month",
              month,
              "approved_row_count",
              approved.size(),
              "export_row_count",
              delivered.size(),
              "job_id",
              result.get("id")));
  }

  private void execute(Run run, Map<String, Object> captured) {
    telemetry.restore(captured);
    run.state = "running";
    boolean reportFixture = run.caseId.equals("case-202") || run.caseId.equals("case-203");
    if (reportFixture) reportFixtureLock.lock();
    try {
      telemetry.emit(
          "info",
          "Benchmark workload started",
          Map.of("run_id", run.runId, "case_id", run.caseId, "seed", run.seed, "noise", run.noise),
          null);
      long team = team(run, "Delivery team"),
          project = project(run, team, "Client reporting"),
          employee = employee(run, "Reporting specialist");
      String month = run.fixture.get("month").toString();
      String date = YearMonth.parse(month).atDay(12).toString();
      BigDecimal approvedHours = amount(run.fixture.get("approvedHours"));
      BigDecimal extraHours = amount(run.fixture.get("extraHours"));
      BigDecimal peerHours = amount(run.fixture.get("peerHours"));
      switch (run.caseId) {
        case "case-101" -> {
          LocalDate firstWorkDay = YearMonth.parse(month).atEndOfMonth();
          LocalDate monday =
              firstWorkDay.with(
                  java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          run.fixture.put("weekStart", monday.toString());
          for (int day = 0; day < 3; day++)
            entry(
                run,
                project,
                employee,
                firstWorkDay.plusDays(day).toString(),
                run.fixture.get("dailyHours"));
          approve(run, submit(run, project, employee, month));
          approve(
              run, submit(run, project, employee, YearMonth.parse(month).plusMonths(1).toString()));
          var report =
              success(
                  run,
                  "overtime-report",
                  "GET",
                  "/api/workflow/reports/overtime?employeeId=" + employee + "&weekStart=" + monday,
                  null);
          run.observations.put("overtime", report);
          BigDecimal hours =
              sum(
                  run,
                  r ->
                      r.employeeId == employee
                          && "APPROVED".equals(r.status)
                          && !r.workDate.isBefore(monday)
                          && r.workDate.isBefore(monday.plusDays(7)));
          BigDecimal extra = hours.subtract(new BigDecimal("40")).max(BigDecimal.ZERO);
          if (amount(report.get("overtimeHours")).compareTo(extra) != 0)
            symptom(
                run,
                "overtime_reconciliation",
                "Weekly overtime disagrees with approved work in the requested week",
                "overtime",
                Map.of(
                    "employee_id",
                    employee,
                    "week_start",
                    monday.toString(),
                    "overtime_hours",
                    report.get("overtimeHours"),
                    "approved_hours",
                    hours));
        }
        case "case-201" -> {
          entry(run, project, employee, date, approvedHours);
          approve(run, submit(run, project, employee, month));
          entry(run, project, employee, date, extraHours);
          reconcileInvoice(run, project, month);
        }
        case "case-202" -> {
          entry(run, project, employee, date, approvedHours);
          approve(run, submit(run, project, employee, month));
          long secondTeam = team(run, "Support team"),
              secondProject = project(run, secondTeam, "Support engagement"),
              secondEmployee = employee(run, "Support specialist");
          entry(run, secondProject, secondEmployee, date, peerHours);
          approve(run, submit(run, secondProject, secondEmployee, month));
          workflow.clearReportCache();
          var first =
              success(
                  run,
                  "team-report-first",
                  "GET",
                  "/api/workflow/reports/team?teamId=" + team + "&month=" + month,
                  null);
          var second =
              success(
                  run,
                  "team-report-second",
                  "GET",
                  "/api/workflow/reports/team?teamId=" + secondTeam + "&month=" + month,
                  null);
          run.observations.put("teamReports", List.of(first, second));
          for (var report : List.of(first, second)) {
            long teamId = ((Number) report.get("teamId")).longValue();
            BigDecimal actual = sum(run, r -> r.teamId == teamId && "APPROVED".equals(r.status));
            if (amount(report.get("totalHours")).compareTo(actual) != 0)
              symptom(
                  run,
                  "team_report_reconciliation",
                  "Team report total disagrees with that team's approved entries",
                  "teamReport",
                  Map.of(
                      "team_id",
                      teamId,
                      "month",
                      month,
                      "reported_hours",
                      report.get("totalHours"),
                      "approved_hours",
                      actual));
          }
        }
        case "case-203" -> {
          entry(run, project, employee, date, approvedHours);
          approve(run, submit(run, project, employee, month));
          workflow.clearReportCache();
          success(
              run,
              "warm-team-report",
              "GET",
              "/api/workflow/reports/team?teamId=" + team + "&month=" + month,
              null);
          entry(run, project, employee, date, extraHours);
          approve(run, submit(run, project, employee, month));
          long secondTeam = team(run, "Support team"),
              secondProject = project(run, secondTeam, "Support engagement"),
              secondEmployee = employee(run, "Support specialist");
          entry(run, secondProject, secondEmployee, date, peerHours);
          approve(run, submit(run, secondProject, secondEmployee, month));
          var first =
              success(
                  run,
                  "refreshed-team-report",
                  "GET",
                  "/api/workflow/reports/team?teamId=" + team + "&month=" + month,
                  null);
          var second =
              success(
                  run,
                  "second-team-report",
                  "GET",
                  "/api/workflow/reports/team?teamId=" + secondTeam + "&month=" + month,
                  null);
          run.observations.put("teamReports", List.of(first, second));
          for (var report : List.of(first, second)) {
            long teamId = ((Number) report.get("teamId")).longValue();
            BigDecimal actual = sum(run, r -> r.teamId == teamId && "APPROVED".equals(r.status));
            if (amount(report.get("totalHours")).compareTo(actual) != 0)
              symptom(
                  run,
                  "team_report_reconciliation",
                  "Team report disagrees with current approved work",
                  "teamReport",
                  Map.of(
                      "team_id",
                      teamId,
                      "month",
                      month,
                      "reported_hours",
                      report.get("totalHours"),
                      "approved_hours",
                      actual));
          }
        }
        case "case-301" -> {
          entry(run, project, employee, date, approvedHours);
          long sheet = submit(run, project, employee, month);
          workflow.coordinateApprovals(run.runId);
          try {
            var a =
                approvals.submit(
                    () ->
                        success(
                            run,
                            "approve-concurrent-a",
                            "POST",
                            "/api/workflow/timesheets/" + sheet + "/approve",
                            Map.of()));
            var b =
                approvals.submit(
                    () ->
                        success(
                            run,
                            "approve-concurrent-b",
                            "POST",
                            "/api/workflow/timesheets/" + sheet + "/approve",
                            Map.of()));
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
          } finally {
            workflow.finishApprovals(run.runId);
          }
          var charges =
              workflow.ledger().stream()
                  .filter(c -> run.runId.equals(c.runId) && c.sheetId == sheet)
                  .toList();
          run.observations.put(
              "approval",
              Map.of(
                  "sheetId",
                  sheet,
                  "ledgerEntryCount",
                  charges.size(),
                  "chargedHours",
                  charges.stream().map(c -> c.hours).reduce(BigDecimal.ZERO, BigDecimal::add)));
          if (charges.size() != 1)
            symptom(
                run,
                "budget_ledger_reconciliation",
                "Budget ledger contains more than one charge for the approved timesheet",
                "approve",
                Map.of(
                    "sheet_id",
                    sheet,
                    "project_id",
                    project,
                    "ledger_entry_count",
                    charges.size()));
        }
        case "case-302" -> {
          entry(run, project, employee, date, approvedHours);
          long firstSheet = submit(run, project, employee, month);
          long secondEmployee = employee(run, "Second approver subject");
          entry(run, project, secondEmployee, date, approvedHours);
          long secondSheet = submit(run, project, secondEmployee, month);
          workflow.coordinateApprovals(run.runId);
          List<Integer> statuses;
          try {
            var a =
                approvals.submit(
                    () ->
                        call(
                            run,
                            "approve-budget-a",
                            "POST",
                            "/api/workflow/timesheets/" + firstSheet + "/approve",
                            Map.of()));
            var b =
                approvals.submit(
                    () ->
                        call(
                            run,
                            "approve-budget-b",
                            "POST",
                            "/api/workflow/timesheets/" + secondSheet + "/approve",
                            Map.of()));
            statuses =
                List.of(a.get(15, TimeUnit.SECONDS).status, b.get(15, TimeUnit.SECONDS).status);
          } finally {
            workflow.finishApprovals(run.runId);
          }
          BigDecimal charged =
              workflow.ledger().stream()
                  .filter(c -> run.runId.equals(c.runId) && c.projectId == project)
                  .map(c -> c.hours)
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
          BigDecimal budget =
              workflow.projects().stream()
                  .filter(p -> p.id == project)
                  .findFirst()
                  .orElseThrow()
                  .budgetHours;
          run.observations.put(
              "budget",
              Map.of("projectId", project, "chargedHours", charged, "approvalStatuses", statuses));
          if (charged.compareTo(budget) > 0)
            symptom(
                run,
                "project_budget_reconciliation",
                "Approved ledger hours exceed the configured project budget",
                "approve",
                Map.of("project_id", project, "charged_hours", charged, "budget_hours", budget));
          if (statuses.stream().anyMatch(status -> status != 200 && status != 409))
            throw new IllegalStateException("Approval returned an unexpected response");
        }
        case "case-401" -> {
          long entry = entry(run, project, employee, date, approvedHours);
          approve(run, submit(run, project, employee, month));
          success(
              run,
              "close-period",
              "POST",
              "/api/workflow/periods/close",
              Map.of("teamId", team, "month", month));
          BigDecimal before =
              records(run).stream().filter(r -> r.id == entry).findFirst().orElseThrow().hours;
          var response =
              call(
                  run,
                  "edit-closed-entry",
                  "PUT",
                  "/api/workflow/entries/" + entry,
                  entryInput(project, employee, date, approvedHours.add(new BigDecimal("1.25"))));
          BigDecimal after =
              records(run).stream().filter(r -> r.id == entry).findFirst().orElseThrow().hours;
          boolean accepted = response.status < 400;
          run.observations.put(
              "lockedEdit",
              Map.of(
                  "entryId",
                  entry,
                  "beforeHours",
                  before,
                  "afterHours",
                  after,
                  "accepted",
                  accepted,
                  "httpStatus",
                  response.status));
          if (before.compareTo(after) != 0)
            symptom(
                run,
                "closed_period_reconciliation",
                "Approved work changed after the reporting period was closed",
                "saveRecord",
                Map.of(
                    "entry_id",
                    entry,
                    "team_id",
                    team,
                    "month",
                    month,
                    "before_hours",
                    before,
                    "after_hours",
                    after));
        }
        case "case-402" -> {
          entry(run, project, employee, date, approvedHours);
          approve(run, submit(run, project, employee, month));
          reconcileExport(run, team, month);
        }
        case "case-501" -> {
          entry(run, project, employee, date, approvedHours);
          approve(run, submit(run, project, employee, month));
          entry(run, project, employee, date, extraHours);
          reconcileInvoice(run, project, month);
          reconcileExport(run, team, month);
        }
        case "case-901" -> {
          String delivery = run.runId + "-delivery";
          var initial =
              call(
                  run,
                  "delivery-first-attempt",
                  "POST",
                  "/api/workflow/deliveries",
                  Map.of("deliveryId", delivery, "simulateTimeout", true));
          var retry =
              success(
                  run,
                  "delivery-retry",
                  "POST",
                  "/api/workflow/deliveries",
                  Map.of("deliveryId", delivery, "simulateTimeout", true));
          run.observations.put("dependency", retry);
          run.observations.put("dependencyFirstStatus", initial.status);
          if (!Boolean.TRUE.equals(retry.get("delivered")))
            symptom(
                run,
                "delivery_unrecovered",
                "Delivery did not recover after retry",
                "deliver",
                Map.of("delivery_id", delivery));
        }
        case "case-902" -> {
          var rejected =
              call(
                  run,
                  "invalid-entry",
                  "POST",
                  "/api/workflow/entries",
                  entryInput(project, employee, date, 25));
          long valid = entry(run, project, employee, date, approvedHours);
          run.observations.put(
              "validation", Map.of("invalidStatus", rejected.status, "validEntryId", valid));
          if (rejected.status != 400)
            symptom(
                run,
                "input_validation",
                "Out-of-range hours were not rejected as a client error",
                "saveRecord",
                Map.of("http.status_code", rejected.status));
          else
            telemetry.emit(
                "info",
                "Valid follow-up entry accepted after input rejection",
                Map.of(
                    "recovery",
                    true,
                    "entry_id",
                    valid,
                    "run_id",
                    run.runId,
                    "case_id",
                    run.caseId),
                null);
        }
        case "case-903" -> {
          entry(run, project, employee, date, approvedHours);
          approve(run, submit(run, project, employee, month));
          success(
              run,
              "close-period",
              "POST",
              "/api/workflow/periods/close",
              Map.of("teamId", team, "month", month));
          reconcileInvoice(run, project, month);
          reconcileExport(run, team, month);
        }
        default -> throw new IllegalStateException("Case workload is unavailable");
      }
      for (int i = 0; i < run.noise / 5; i++)
        call(
            null,
            "unrelated-traffic",
            "GET",
            i % 2 == 0 ? "/api/workflow/teams" : "/api/workflow/dependency/ack?delayMs=0",
            null);
      run.completedAt = Instant.now();
      run.state = "completed";
      telemetry.emit(
          "info",
          "Benchmark workload completed",
          Map.of(
              "run_id",
              run.runId,
              "case_id",
              run.caseId,
              "outcome",
              run.outcome,
              "request_count",
              run.requestIds.size()),
          null);
    } catch (Exception error) {
      run.outcome = "unhealthy";
      run.observations.put(
          "workloadError", Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
      run.completedAt = Instant.now();
      run.state = "failed";
      telemetry.emit(
          "error",
          "Benchmark workload failed",
          Map.of(
              "run_id",
              run.runId,
              "case_id",
              run.caseId,
              "code.file",
              "src/main/java/dev/sandbox/timesheet/benchmark/BenchmarkService.java",
              "code.function",
              "execute"),
          error);
    } finally {
      if (reportFixture) reportFixtureLock.unlock();
      telemetry.clearContext();
    }
  }

  @org.springframework.context.event.EventListener
  public void webServerReady(org.springframework.boot.web.context.WebServerInitializedEvent event) {
    this.port = event.getWebServer().getPort();
  }

  @PreDestroy
  public void stop() {
    workers.shutdownNow();
    approvals.shutdownNow();
  }
}
