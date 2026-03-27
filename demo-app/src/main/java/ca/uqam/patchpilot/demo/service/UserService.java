package ca.uqam.patchpilot.demo.service;

import ca.uqam.patchpilot.demo.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * User lookup service.
 *
 * VULNERABILITY 1 — SQL Injection (SonarQube rule: java:S2077)
 *
 * The search method builds a native SQL query by concatenating the caller-
 * supplied {@code department} value directly into the query string. An attacker
 * can terminate the intended query and append arbitrary SQL, potentially
 * exfiltrating or modifying any data the database user has access to.
 *
 * Secure fix: use a parameterised query:
 *   entityManager.createNativeQuery(
 *       "SELECT * FROM users WHERE department = :dept", User.class)
 *       .setParameter("dept", department);
 */
@Service
public class UserService {

    @PersistenceContext
    private EntityManager entityManager;

    @SuppressWarnings("unchecked")
    public List<User> findByDepartment(String department) {
        // VULNERABILITY: unsanitised input concatenated into a native SQL query
        String query = "SELECT * FROM users WHERE department = '" + department + "'";
        return entityManager.createNativeQuery(query, User.class).getResultList();
    }
}
