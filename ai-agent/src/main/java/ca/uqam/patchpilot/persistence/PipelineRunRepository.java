package ca.uqam.patchpilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {

    // Used to check whether a run for this task was already created —
    // prevents double-processing when the poller picks up a PENDING event
    // that the webhook controller already dispatched.
    Optional<PipelineRun> findBySonarTaskId(String sonarTaskId);
}
