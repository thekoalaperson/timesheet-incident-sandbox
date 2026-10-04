package dev.sandbox.timesheet;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(
    name = "time_entry",
    uniqueConstraints =
        @UniqueConstraint(
            name = "unique_employee_submission",
            columnNames = {"employee_id", "submission_id"}))
public class TimeEntry {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "employee_id", nullable = false)
  public Long employeeId;

  @Column(nullable = false)
  public LocalDate date;

  @Column(nullable = false, precision = 7, scale = 2)
  public BigDecimal hours;

  public String description;

  @Column(name = "submission_id")
  public String submissionId;

  protected TimeEntry() {}

  public TimeEntry(
      Long employeeId, LocalDate date, BigDecimal hours, String description, String submissionId) {
    this.employeeId = employeeId;
    this.date = date;
    this.hours = hours;
    this.description = description;
    this.submissionId = submissionId;
  }
}
