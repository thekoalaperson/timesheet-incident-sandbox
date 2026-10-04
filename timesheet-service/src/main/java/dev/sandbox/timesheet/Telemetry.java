package dev.sandbox.timesheet;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class Telemetry extends OncePerRequestFilter {
  private final ObjectMapper mapper;
  private final Path file =
      Path.of(System.getenv().getOrDefault("LOG_FILE", "../runtime/service.jsonl"));
  private final ThreadLocal<Map<String, Object>> context =
      ThreadLocal.withInitial(LinkedHashMap::new);
  private final List<Map<String, Object>> incidents = new CopyOnWriteArrayList<>();

  public Telemetry(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public void scenario(String id) {
    context.get().put("scenario_id", id);
  }

  public synchronized void emit(
      String status, String message, Map<String, Object> extra, Throwable error) {
    var attributes = new LinkedHashMap<String, Object>(context.get());
    attributes.put("version", "1.0.0");
    attributes.putAll(extra);
    if (error != null) {
      var buffer = new StringWriter();
      error.printStackTrace(new PrintWriter(buffer));
      attributes.put("error.stack", buffer.toString());
      attributes.putIfAbsent("error.kind", error.getClass().getSimpleName());
    }
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(
          file,
          mapper.writeValueAsString(
                  Map.of(
                      "timestamp",
                      Instant.now().toString(),
                      "service",
                      "timesheet-service",
                      "status",
                      status,
                      "message",
                      message,
                      "attributes",
                      attributes))
              + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      logger.error("Cannot write service telemetry", e);
    }
  }

  public synchronized void incident(
      String scenario,
      String kind,
      String message,
      Throwable error,
      String function,
      Map<String, Object> details) {
    var incident = new LinkedHashMap<String, Object>();
    incident.put("scenarioId", scenario);
    incident.put("kind", kind);
    incident.put("message", message);
    incident.put("timestamp", Instant.now().toString());
    incident.put("severity", error == null ? "warn" : "error");
    incidents.add(incident);
    if (incidents.size() > 100) incidents.removeFirst();
    var attrs = new LinkedHashMap<String, Object>(details);
    attrs.put("scenario_id", scenario);
    attrs.put("error.kind", kind);
    attrs.put("code.file", "src/main/java/dev/sandbox/timesheet/TimesheetService.java");
    attrs.put("code.function", function);
    emit(error == null ? "warn" : "error", message, attrs, error);
  }

  public Map<String, Object> health() {
    return Map.of(
        "status",
        incidents.stream().anyMatch(i -> i.get("severity").equals("error"))
            ? "unhealthy"
            : incidents.isEmpty() ? "healthy" : "degraded",
        "service",
        "timesheet-service",
        "incidents",
        List.copyOf(incidents),
        "timestamp",
        Instant.now().toString());
  }

  public void reset() {
    incidents.clear();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var ctx = context.get();
    ctx.clear();
    ctx.put("request_id", UUID.randomUUID().toString());
    ctx.put("trace_id", UUID.randomUUID().toString().replace("-", ""));
    ctx.put("http.method", request.getMethod());
    ctx.put("http.route", request.getRequestURI());
    response.setHeader("X-Request-Id", ctx.get("request_id").toString());
    response.setHeader("X-Trace-Id", ctx.get("trace_id").toString());
    long start = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      emit(
          response.getStatus() >= 500 ? "error" : response.getStatus() >= 400 ? "warn" : "info",
          "HTTP request completed",
          Map.of(
              "http.status_code",
              response.getStatus(),
              "duration_ms",
              (System.nanoTime() - start) / 1000000),
          null);
      context.remove();
    }
  }
}
