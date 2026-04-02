package ca.uqam.patchpilot.demo.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Simple employee record entity — exists to give the SQL injection
 * vulnerability a realistic data model to operate on.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String email;
    private String department;

    public User() {}

    public User(String username, String email, String department) {
        this.username = username;
        this.email = email;
        this.department = department;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getDepartment() { return department; }
}
