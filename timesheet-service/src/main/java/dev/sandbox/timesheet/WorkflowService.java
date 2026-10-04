package dev.sandbox.timesheet;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkflowService {
  @Entity(name = "WorkflowTeam")
  @Table(name = "workflow_team")
  public static class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String name;
    public String runId;
  }

  @Entity(name = "WorkflowProject")
  @Table(name = "workflow_project")
  public static class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long teamId;
    public String name;

    @Column(precision = 12, scale = 2)
    public BigDecimal budgetHours;

    @Column(precision = 12, scale = 2)
    public BigDecimal hourlyRate;

    public String runId;
  }

  @Entity(name = "WorkflowRecord")
  @Table(name = "workflow_record")
  public static class WorkRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long teamId;
    public Long projectId;
    public Long employeeId;
    public LocalDate workDate;

    @Column(precision = 12, scale = 2)
    public BigDecimal hours;

    public String description;
    public String status = "DRAFT";
    public Long sheetId;
    public Instant createdAt;
    public String runId;
  }

  @Entity(name = "WorkflowSheet")
  @Table(name = "workflow_sheet")
  public static class Sheet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long teamId;
    public Long projectId;
    public Long employeeId;

    @Column(name = "period_month")
    public String month;

    public String status = "SUBMITTED";

    @Column(precision = 12, scale = 2)
    public BigDecimal hours;

    public String runId;
  }

  @Entity(name = "WorkflowCharge")
  @Table(name = "workflow_charge")
  public static class Charge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long sheetId;
    public Long projectId;
    public Long teamId;

    @Column(precision = 12, scale = 2)
    public BigDecimal hours;

    public Instant timestamp;
    public String runId;
  }

  @Entity(name = "WorkflowPeriod")
  @Table(name = "workflow_period")
  public static class Period {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long teamId;

    @Column(name = "period_month")
    public String month;

    public boolean closed;
    public Instant closedAt;
    public String runId;
  }

  @Entity(name = "WorkflowAudit")
  @Table(name = "workflow_audit")
  public static class Audit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String action;
    public Long entityId;
    public String beforeValue;
    public String afterValue;
    public Instant timestamp;
    public String runId;
    public String requestId;
    public String traceId;
  }

  public static class ExportJob {
    public String id;
    public Long teamId;
    public String month;
    public volatile String state = "queued";
    public volatile List<WorkRecord> rows = List.of();
    public volatile int rowCount;
    public Instant createdAt = Instant.now();
    public volatile Instant completedAt;
    public String runId;
    public volatile String error;
  }

  @PersistenceContext private EntityManager em;
  private final TransactionTemplate transactions;
  private final Telemetry telemetry;
  private final EmployeeRepository employees;
  private final ObjectMapper mapper;
  private volatile int port;
  private final ExecutorService jobs =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(64),
          new ThreadPoolExecutor.AbortPolicy());
  private final Map<String, ExportJob> exports = new ConcurrentHashMap<>();
  private final Map<String, Instant> fixtureClocks = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> reportCache = new ConcurrentHashMap<>();
  private final Map<String, CyclicBarrier> approvalBarriers = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> deliveryAttempts = new ConcurrentHashMap<>();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
  private static final String SOURCE = "src/main/java/dev/sandbox/timesheet/WorkflowService.java";

  public WorkflowService(
      TransactionTemplate transactions,
      Telemetry telemetry,
      EmployeeRepository employees,
      ObjectMapper mapper,
      @Value("${server.port:8080}") int port) {
    this.transactions = transactions;
    this.telemetry = telemetry;
    this.employees = employees;
    this.mapper = mapper;
    this.port = port;
  }

  private <T> T tx(Supplier<T> work) {
    return transactions.execute(status -> work.get());
  }

  private <T> T require(Class<T> type, Long id) {
    T value = id == null ? null : em.find(type, id);
    if (value == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, type.getSimpleName() + " not found");
    return value;
  }

  private <T> List<T> all(Class<T> type, String entity) {
    return em.createQuery("select e from " + entity + " e order by e.id", type).getResultList();
  }

  private String runId() {
    return telemetry.value("run_id");
  }

  private Map<String, Object> attributes(String function, Map<String, Object> detail) {
    var attrs = new LinkedHashMap<String, Object>(detail);
    attrs.put("code.file", SOURCE);
    attrs.put("code.function", function);
    return attrs;
  }

  private void event(String function, String message, Map<String, Object> detail) {
    telemetry.emit("info", message, attributes(function, detail), null);
  }

  private void audit(String action, Long id, String before, String after) {
    var item = new Audit();
    item.action = action;
    item.entityId = id;
    item.beforeValue = before;
    item.afterValue = after;
    item.timestamp = Instant.now();
    item.runId = runId();
    item.requestId = telemetry.value("request_id");
    item.traceId = telemetry.value("trace_id");
    em.persist(item);
  }

  private static void money(BigDecimal amount, String name) {
    if (amount == null
        || amount.signum() < 0
        || amount.stripTrailingZeros().scale() > 2
        || amount.compareTo(new BigDecimal("9999999999.99")) > 0)
      throw new IllegalArgumentException(name + " must be nonnegative with at most two decimals");
  }

  private static void text(String value, String name) {
    if (value == null || value.isBlank() || value.length() > 255)
      throw new IllegalArgumentException(name + " is required and at most 255 characters");
  }

  public List<Team> teams() {
    return tx(() -> all(Team.class, "WorkflowTeam"));
  }

  public Team createTeam(Map<String, Object> input) {
    return tx(
        () -> {
          var team = new Team();
          team.name = string(input, "name");
          text(team.name, "name");
          team.runId = runId();
          em.persist(team);
          audit("team.created", team.id, "", team.name);
          event("createTeam", "Team created", Map.of("team_id", team.id));
          return team;
        });
  }

  public List<Project> projects() {
    return tx(() -> all(Project.class, "WorkflowProject"));
  }

  public Project createProject(Map<String, Object> input) {
    return tx(
        () -> {
          var p = new Project();
          p.teamId = number(input, "teamId");
          require(Team.class, p.teamId);
          p.name = string(input, "name");
          text(p.name, "name");
          p.budgetHours = decimal(input, "budgetHours");
          p.hourlyRate = decimal(input, "hourlyRate");
          money(p.budgetHours, "budgetHours");
          money(p.hourlyRate, "hourlyRate");
          p.runId = runId();
          em.persist(p);
          audit("project.created", p.id, "", p.name);
          event(
              "createProject",
              "Project budget configured",
              Map.of("team_id", p.teamId, "project_id", p.id, "budget_hours", p.budgetHours));
          return p;
        });
  }

  public List<WorkRecord> records() {
    return tx(() -> all(WorkRecord.class, "WorkflowRecord"));
  }

  public WorkRecord saveRecord(Map<String, Object> input, Long id) {
    return tx(
        () -> {
          Long projectId = number(input, "projectId");
          var project = require(Project.class, projectId);
          Long employeeId = number(input, "employeeId");
          if (!employees.existsById(employeeId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
          LocalDate date = LocalDate.parse(requiredString(input, "workDate"));
          BigDecimal hours = decimal(input, "hours");
          money(hours, "hours");
          if (hours.compareTo(new BigDecimal("24")) > 0)
            throw new IllegalArgumentException("hours must not exceed 24");
          var record = id == null ? new WorkRecord() : require(WorkRecord.class, id);
          boolean closed =
              all(Period.class, "WorkflowPeriod").stream()
                  .anyMatch(
                      p ->
                          p.teamId.equals(project.teamId)
                              && p.month.equals(YearMonth.from(date).toString())
                              && p.closed);
          if (id == null && closed)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Period is closed");
          if (id != null && closed && "SUBMITTED".equals(record.status))
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Submitted entry in a closed period is locked");
          String before = record.hours == null ? "" : record.hours.toPlainString();
          record.teamId = project.teamId;
          record.projectId = projectId;
          record.employeeId = employeeId;
          record.workDate = date;
          record.hours = hours;
          record.description = string(input, "description");
          if (record.description != null && record.description.length() > 255)
            throw new IllegalArgumentException("description must contain at most 255 characters");
          if (id == null) {
            record.createdAt =
                (runId() == null
                    ? Instant.now()
                    : fixtureClocks.getOrDefault(runId(), Instant.now()));
            record.runId = runId();
            em.persist(record);
          }
          audit(
              id == null ? "entry.created" : "entry.edited",
              record.id,
              before,
              hours.toPlainString());
          event(
              "saveRecord",
              id == null ? "Work entry recorded" : "Work entry updated",
              Map.of(
                  "entry_id",
                  record.id,
                  "team_id",
                  record.teamId,
                  "project_id",
                  projectId,
                  "work_date",
                  date.toString(),
                  "hours",
                  hours,
                  "entry_status",
                  record.status,
                  "period_closed",
                  closed));
          return record;
        });
  }

  public List<Sheet> sheets() {
    return tx(() -> all(Sheet.class, "WorkflowSheet"));
  }

  public Sheet submit(Map<String, Object> input) {
    return tx(
        () -> {
          Long projectId = number(input, "projectId"), employeeId = number(input, "employeeId");
          var project = require(Project.class, projectId);
          String month = YearMonth.parse(requiredString(input, "month")).toString();
          if (all(Period.class, "WorkflowPeriod").stream()
              .anyMatch(p -> p.teamId.equals(project.teamId) && p.month.equals(month) && p.closed))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Period is closed");
          var rows =
              all(WorkRecord.class, "WorkflowRecord").stream()
                  .filter(
                      r ->
                          r.projectId.equals(projectId)
                              && r.employeeId.equals(employeeId)
                              && YearMonth.from(r.workDate).toString().equals(month)
                              && "DRAFT".equals(r.status))
                  .toList();
          if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No draft entries to submit");
          var sheet = new Sheet();
          sheet.projectId = projectId;
          sheet.teamId = project.teamId;
          sheet.employeeId = employeeId;
          sheet.month = month;
          sheet.hours = sum(rows);
          sheet.runId = runId();
          em.persist(sheet);
          for (var r : rows) {
            r.status = "SUBMITTED";
            r.sheetId = sheet.id;
          }
          audit("timesheet.submitted", sheet.id, "DRAFT", "SUBMITTED");
          event(
              "submit",
              "Timesheet submitted",
              Map.of(
                  "sheet_id",
                  sheet.id,
                  "project_id",
                  projectId,
                  "team_id",
                  sheet.teamId,
                  "hours",
                  sheet.hours));
          return sheet;
        });
  }

  public Sheet approve(Long id) {
    return tx(
        () -> {
          var sheet = require(Sheet.class, id);
          if ("APPROVED".equals(sheet.status)) {
            event("approve", "Approval replay returned existing state", Map.of("sheet_id", id));
            return sheet;
          }
          if (!"SUBMITTED".equals(sheet.status))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Timesheet is not submitted");
          var p = require(Project.class, sheet.projectId);
          BigDecimal used =
              all(Charge.class, "WorkflowCharge").stream()
                  .filter(c -> c.projectId.equals(p.id))
                  .map(c -> c.hours)
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
          if (used.add(sheet.hours).compareTo(p.budgetHours) > 0)
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Approval exceeds project budget");
          CyclicBarrier barrier = runId() == null ? null : approvalBarriers.get(runId());
          if (barrier != null)
            try {
              barrier.await(400, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | BrokenBarrierException e) {
              event("approve", "Approval rendezvous released", Map.of("sheet_id", id));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("Approval interrupted", e);
            }
          var charge = new Charge();
          charge.sheetId = id;
          charge.projectId = sheet.projectId;
          charge.teamId = sheet.teamId;
          charge.hours = sheet.hours;
          charge.timestamp = Instant.now();
          charge.runId = runId();
          em.persist(charge);
          sheet.status = "APPROVED";
          for (var r : all(WorkRecord.class, "WorkflowRecord"))
            if (id.equals(r.sheetId)) r.status = "APPROVED";
          audit("timesheet.approved", id, "SUBMITTED", "APPROVED");
          event(
              "approve",
              "Approval ledger charge posted",
              Map.of(
                  "sheet_id",
                  id,
                  "ledger_id",
                  charge.id,
                  "project_id",
                  sheet.projectId,
                  "team_id",
                  sheet.teamId,
                  "hours",
                  sheet.hours));
          return sheet;
        });
  }

  public List<Charge> ledger() {
    return tx(() -> all(Charge.class, "WorkflowCharge"));
  }

  public List<Audit> audit() {
    return tx(() -> all(Audit.class, "WorkflowAudit"));
  }

  public List<Period> periods() {
    return tx(() -> all(Period.class, "WorkflowPeriod"));
  }

  public Period close(Map<String, Object> input) {
    return tx(
        () -> {
          Long teamId = number(input, "teamId");
          require(Team.class, teamId);
          String month = YearMonth.parse(requiredString(input, "month")).toString();
          if (all(WorkRecord.class, "WorkflowRecord").stream()
              .anyMatch(
                  r ->
                      r.teamId.equals(teamId)
                          && YearMonth.from(r.workDate).toString().equals(month)
                          && !"APPROVED".equals(r.status)))
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "All period entries must be approved before closing");
          var existing =
              all(Period.class, "WorkflowPeriod").stream()
                  .filter(p -> p.teamId.equals(teamId) && p.month.equals(month))
                  .findFirst();
          var period = existing.orElseGet(Period::new);
          period.teamId = teamId;
          period.month = month;
          period.closed = true;
          period.closedAt = Instant.now();
          period.runId = runId();
          if (period.id == null) em.persist(period);
          audit("period.closed", period.id, "OPEN", "CLOSED");
          event("close", "Team reporting period closed", Map.of("team_id", teamId, "month", month));
          return period;
        });
  }

  public Map<String, Object> invoice(Long projectId, String month) {
    return tx(
        () -> {
          var project = require(Project.class, projectId);
          YearMonth period = YearMonth.parse(month);
          var rows =
              all(WorkRecord.class, "WorkflowRecord").stream()
                  .filter(
                      r ->
                          r.projectId.equals(projectId)
                              && YearMonth.from(r.workDate).equals(period))
                  .toList();
          BigDecimal hours = sum(rows);
          event(
              "invoice",
              "Invoice preview calculated",
              Map.of(
                  "project_id",
                  projectId,
                  "team_id",
                  project.teamId,
                  "month",
                  month,
                  "invoice_hours",
                  hours,
                  "source_entry_count",
                  rows.size()));
          return Map.of(
              "projectId",
              projectId,
              "month",
              month,
              "hours",
              hours,
              "amount",
              hours.multiply(project.hourlyRate));
        });
  }

  public Map<String, Object> teamReport(Long teamId, String month) {
    return tx(
        () -> {
          require(Team.class, teamId);
          YearMonth period = YearMonth.parse(month);
          var cached = reportCache.get(month);
          if (cached != null) {
            event(
                "teamReport",
                "Team report served from cache",
                Map.of(
                    "team_id",
                    teamId,
                    "month",
                    month,
                    "cache_hit",
                    true,
                    "total_hours",
                    cached.get("totalHours")));
            return Map.of("teamId", teamId, "month", month, "totalHours", cached.get("totalHours"));
          }
          var rows =
              all(WorkRecord.class, "WorkflowRecord").stream()
                  .filter(
                      r ->
                          r.teamId.equals(teamId)
                              && "APPROVED".equals(r.status)
                              && YearMonth.from(r.workDate).equals(period))
                  .toList();
          var result =
              Map.<String, Object>of("teamId", teamId, "month", month, "totalHours", sum(rows));
          if (reportCache.size() > 100) reportCache.clear();
          reportCache.put(month, result);
          event(
              "teamReport",
              "Team report calculated",
              Map.of(
                  "team_id",
                  teamId,
                  "month",
                  month,
                  "cache_hit",
                  false,
                  "total_hours",
                  result.get("totalHours")));
          return result;
        });
  }

  public Map<String, Object> overtime(Long employeeId, LocalDate weekStart) {
    return tx(
        () -> {
          if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY)
            throw new IllegalArgumentException("weekStart must be a Monday");
          if (!employees.existsById(employeeId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
          Map<String, BigDecimal> weekly = new TreeMap<>();
          for (var r : all(WorkRecord.class, "WorkflowRecord")) {
            if (r.employeeId.equals(employeeId)
                && "APPROVED".equals(r.status)
                && !r.workDate.isBefore(weekStart)
                && r.workDate.isBefore(weekStart.plusDays(7))) {
              String key =
                  r.workDate.getYear()
                      + "-W"
                      + r.workDate.get(WeekFields.ISO.weekOfWeekBasedYear());
              weekly.merge(key, r.hours, BigDecimal::add);
            }
          }
          var groups = new ArrayList<Map<String, Object>>();
          BigDecimal total = BigDecimal.ZERO;
          for (var entry : weekly.entrySet()) {
            BigDecimal extra = entry.getValue().subtract(new BigDecimal("40")).max(BigDecimal.ZERO);
            total = total.add(extra);
            groups.add(
                Map.of("week", entry.getKey(), "hours", entry.getValue(), "overtimeHours", extra));
          }
          event(
              "overtime",
              "Weekly overtime calculated",
              Map.of(
                  "employee_id",
                  employeeId,
                  "week_start",
                  weekStart.toString(),
                  "group_count",
                  groups.size(),
                  "overtime_hours",
                  total));
          return Map.of(
              "employeeId",
              employeeId,
              "weekStart",
              weekStart.toString(),
              "weeks",
              groups,
              "overtimeHours",
              total);
        });
  }

  public ExportJob export(Map<String, Object> input) {
    Long teamId = number(input, "teamId");
    tx(() -> require(Team.class, teamId));
    String month = YearMonth.parse(requiredString(input, "month")).toString();
    if (exports.size() >= 200) exports.entrySet().removeIf(e -> e.getValue().completedAt != null);
    if (exports.size() >= 200)
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Export capacity reached");
    var job = new ExportJob();
    job.id = UUID.randomUUID().toString();
    job.teamId = teamId;
    job.month = month;
    job.runId = runId();
    exports.put(job.id, job);
    Map<String, Object> context = telemetry.capture();
    context.put("parent_request_id", context.getOrDefault("request_id", ""));
    context.put("parent_trace_id", context.getOrDefault("trace_id", ""));
    context.put("trace_id", UUID.randomUUID().toString().replace("-", ""));
    context.put("job_id", job.id);
    event(
        "export", "Export job queued", Map.of("job_id", job.id, "team_id", teamId, "month", month));
    try {
      jobs.submit(
          () -> {
            telemetry.restore(context);
            try {
              job.state = "running";
              event(
                  "executeExport",
                  "Export job started",
                  Map.of("job_id", job.id, "team_id", teamId, "month", month));
              YearMonth period = YearMonth.parse(month);
              job.rows =
                  tx(
                      () ->
                          all(WorkRecord.class, "WorkflowRecord").stream()
                              .filter(
                                  r ->
                                      r.teamId.equals(teamId)
                                          && "APPROVED".equals(r.status)
                                          && YearMonth.from(r.createdAt.atZone(ZoneOffset.UTC))
                                              .equals(period))
                              .toList());
              job.rowCount = job.rows.size();
              job.completedAt = Instant.now();
              job.state = "completed";
              event(
                  "executeExport",
                  "Export artifact completed",
                  Map.of(
                      "job_id",
                      job.id,
                      "team_id",
                      teamId,
                      "month",
                      month,
                      "row_count",
                      job.rowCount));
            } catch (Exception e) {
              job.error = e.getMessage();
              job.completedAt = Instant.now();
              job.state = "failed";
              telemetry.emit(
                  "error",
                  "Export job failed",
                  attributes("executeExport", Map.of("job_id", job.id, "team_id", teamId)),
                  e);
            } finally {
              telemetry.clearContext();
            }
          });
    } catch (RejectedExecutionException error) {
      exports.remove(job.id);
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Export workers are busy");
    }
    return job;
  }

  public ExportJob exportJob(String id) {
    var job = exports.get(id);
    if (job == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Export job not found");
    return job;
  }

  public List<ExportJob> exports() {
    return List.copyOf(exports.values());
  }

  public Map<String, Object> deliver(Map<String, Object> input) {
    String key = string(input, "deliveryId");
    text(key, "deliveryId");
    if (!deliveryAttempts.containsKey(key) && deliveryAttempts.size() >= 1000)
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Delivery capacity reached");
    int attempt = deliveryAttempts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    boolean delay = Boolean.TRUE.equals(input.get("simulateTimeout")) && attempt == 1;
    try {
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://127.0.0.1:"
                          + port
                          + "/api/workflow/dependency/ack?delayMs="
                          + (delay ? 150 : 0)))
              .timeout(java.time.Duration.ofMillis(delay ? 60 : 2000))
              .GET();
      if (runId() != null) request.header("X-Benchmark-run-id", runId());
      if (telemetry.value("case_id") != null)
        request.header("X-Benchmark-case-id", telemetry.value("case_id"));
      var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new java.io.IOException("Dependency returned " + response.statusCode());
    } catch (java.net.http.HttpTimeoutException error) {
      telemetry.emit(
          "warn",
          "Outbound delivery timed out; retry remains available",
          attributes(
              "deliver",
              Map.of("delivery_id", key, "attempt", attempt, "error.kind", "dependency_timeout")),
          error);
      throw new ResponseStatusException(
          HttpStatus.GATEWAY_TIMEOUT, "Delivery acknowledgement timed out", error);
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Delivery dependency unavailable", error);
    }
    event(
        "deliver",
        "Outbound delivery acknowledged",
        Map.of("delivery_id", key, "attempt", attempt, "recovery", attempt > 1));
    return Map.of("deliveryId", key, "attempts", attempt, "delivered", true);
  }

  public Map<String, Object> acknowledgement(int delayMs) {
    if (delayMs < 0 || delayMs > 200) throw new IllegalArgumentException("delayMs must be 0..200");
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Acknowledgement interrupted", error);
    }
    event("acknowledgement", "Delivery dependency acknowledged", Map.of("latency_ms", delayMs));
    return Map.of("acknowledged", true);
  }

  public void unregisterFixtureClock(String id) {
    fixtureClocks.remove(id);
  }

  public void registerFixtureClock(String id, Instant clock) {
    fixtureClocks.put(id, clock);
  }

  public void coordinateApprovals(String id) {
    approvalBarriers.put(id, new CyclicBarrier(2));
  }

  public void finishApprovals(String id) {
    approvalBarriers.remove(id);
  }

  public void clearReportCache() {
    reportCache.clear();
  }

  @org.springframework.context.event.EventListener
  public void webServerReady(org.springframework.boot.web.context.WebServerInitializedEvent event) {
    this.port = event.getWebServer().getPort();
  }

  @PreDestroy
  public void stop() {
    jobs.shutdownNow();
  }

  private static BigDecimal sum(List<WorkRecord> rows) {
    return rows.stream().map(r -> r.hours).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  static String requiredString(Map<String, Object> input, String key) {
    String value = string(input, key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
    return value;
  }

  static String string(Map<String, Object> input, String key) {
    Object value = input.get(key);
    return value == null ? null : value.toString();
  }

  static Long number(Map<String, Object> input, String key) {
    Object value = input.get(key);
    if (value == null) throw new IllegalArgumentException(key + " is required");
    try {
      return Long.valueOf(value.toString());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be an integer");
    }
  }

  static BigDecimal decimal(Map<String, Object> input, String key) {
    Object value = input.get(key);
    if (value == null) throw new IllegalArgumentException(key + " is required");
    try {
      return new BigDecimal(value.toString());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be a decimal number");
    }
  }
}
