package dev.sandbox.timesheet;

import jakarta.persistence.*;

@Entity
public class Employee {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false)
  public String name;

  public String department;

  protected Employee() {}

  public Employee(String name, String department) {
    this.name = name;
    this.department = department;
  }
}
