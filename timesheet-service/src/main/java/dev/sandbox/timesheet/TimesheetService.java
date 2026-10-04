package dev.sandbox.timesheet;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TimesheetService {
  final EmployeeRepository employees;
  final TimeEntryRepository entries;
  final Telemetry telemetry;

  public TimesheetService(
      EmployeeRepository employees, TimeEntryRepository entries, Telemetry telemetry) {
    this.employees = employees;
    this.entries = entries;
    this.telemetry = telemetry;
    if (employees.count() == 0) employees.save(new Employee("Alex Morgan", "Engineering"));
  }

  Employee employee(Long id) {
    return employees
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
  }

  TimeEntry entry(Long id) {
    return entries
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));
  }

  public TimeEntry save(TimeEntry input, Long id) {
    if (input.employeeId == null
        || input.date == null
        || input.hours == null
        || input.hours.signum() < 0
        || input.hours.compareTo(BigDecimal.valueOf(24)) > 0
        || input.hours.stripTrailingZeros().scale() > 2)
      throw new IllegalArgumentException(
          "employeeId, date and hours between 0 and 24 with at most two decimal places are required");
    employee(input.employeeId);
    String key =
        input.submissionId == null || input.submissionId.isBlank() ? null : input.submissionId;
    if (key != null && key.length() > 255)
      throw new IllegalArgumentException("submissionId must contain at most 255 characters");
    if (id == null && key != null) {
      var existing = entries.findByEmployeeIdAndSubmissionId(input.employeeId, key);
      if (existing.isPresent()) return retry(existing.get(), input);
    }
    TimeEntry result =
        id == null
            ? new TimeEntry(input.employeeId, input.date, input.hours, input.description, key)
            : entry(id);
    result.employeeId = input.employeeId;
    result.date = input.date;
    result.hours = input.hours;
    result.description = input.description;
    result.submissionId = key;
    try {
      return entries.saveAndFlush(result);
    } catch (DataIntegrityViolationException error) {
      // Repository transactions roll back failed inserts before we read the winner.
      if (key != null) {
        var existing = entries.findByEmployeeIdAndSubmissionId(input.employeeId, key);
        if (existing.isPresent()) {
          if (id == null) return retry(existing.get(), input);
          throw new ResponseStatusException(
              HttpStatus.CONFLICT, "Submission ID is already used by another entry", error);
        }
      }
      throw error;
    }
  }

  private TimeEntry retry(TimeEntry existing, TimeEntry input) {
    if (!existing.date.equals(input.date)
        || existing.hours.compareTo(input.hours) != 0
        || !Objects.equals(existing.description, input.description))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Submission ID was already used with a different payload");
    return existing;
  }

  public List<TimeEntry> list(Long employeeId, String month) {
    YearMonth period = month == null ? null : YearMonth.parse(month);
    return entries.findAll().stream()
        .filter(e -> employeeId == null || e.employeeId.equals(employeeId))
        .filter(e -> period == null || YearMonth.from(e.date).equals(period))
        .sorted(Comparator.comparing((TimeEntry e) -> e.date).thenComparing(e -> e.id))
        .toList();
  }

  public Map<String, Object> timesheet(Long employeeId, String month) {
    employee(employeeId);
    YearMonth period = YearMonth.parse(month);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();
    List<TimeEntry> rows =
        entries.findAll().stream()
            .filter(
                e ->
                    e.employeeId.equals(employeeId)
                        && !e.date.isBefore(start)
                        && !e.date.isAfter(end))
            .toList();
    BigDecimal total = rows.stream().map(e -> e.hours).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal exact = rows.stream().map(e -> e.hours).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(exact) != 0)
      telemetry.incident(
          "monthly-total",
          "total_mismatch",
          "Monthly total differs from the persisted entry sum",
          null,
          "timesheet",
          Map.of(
              "employee_id",
              employeeId,
              "month",
              month,
              "reported_hours",
              total,
              "expected_hours",
              exact));
    return Map.of("employeeId", employeeId, "month", month, "entries", rows, "totalHours", total);
  }
}
