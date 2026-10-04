package dev.sandbox.timesheet;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
public class TimeEntry {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false)
  public Long employeeId;

  @Column(nullable = false)
  public LocalDate date;

  @Column(nullable = false, precision = 7, scale = 2)
  public BigDecimal hours;

  public String description;
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
