package ca.uqam.patchpilot.demo.controller;

import ca.uqam.patchpilot.demo.model.User;
import ca.uqam.patchpilot.demo.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for user lookup.
 *
 * Delegates to {@link UserService#findByDepartment(String)}, which contains
 * the SQL injection vulnerability (java:S2077). The controller itself passes
 * the raw request parameter directly to the service without sanitisation.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Returns all users belonging to the specified department.
     *
     * Example: GET /api/users/search?department=Engineering
     */
    @GetMapping("/search")
    public ResponseEntity<List<User>> search(@RequestParam String department) {
        return ResponseEntity.ok(userService.findByDepartment(department));
    }
}
