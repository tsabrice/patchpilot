## What
<!-- One sentence: what does this PR change? -->

## Why
<!-- What problem does it solve? Reference the PRD section or week plan task. -->

## How
<!-- Any non-obvious implementation choices. What did you consider and reject? -->

## Test plan
- [ ] Unit tests added or updated
- [ ] Integration test (Testcontainers) covers the happy path
- [ ] Tested locally with `docker compose up`
- [ ] No secrets committed (`detect-secrets scan` passed)

## Checklist
- [ ] Conventional commit messages (`feat`, `fix`, `chore`, etc.)
- [ ] New Spring annotations explained in inline comments (first use in file)
- [ ] External API calls use `RestClient` with explicit timeouts
- [ ] No derived aggregate columns added to schema
