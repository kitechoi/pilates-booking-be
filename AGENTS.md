# AGENTS.md

## Project

PilaSlot is a Spring Boot backend application.

Follow the existing project structure, naming conventions, and architectural patterns before introducing new ones.

## Build and test

- Run the full test suite with:
  `./gradlew test`
- Run the smallest relevant test set while iterating when practical.
- Do not leave the repository in a failing state.

## Code conventions

- Follow the style and patterns already used in nearby code.
- Prefer simple, readable code over unnecessary abstractions.
- Keep controllers focused on HTTP request and response handling.
- Keep business rules in the appropriate service or domain layer.
- Keep persistence concerns in repositories.
- Avoid introducing a new dependency or abstraction when the existing stack already solves the problem.

## Changes

- Keep changes focused on the requested task.
- Avoid unrelated refactoring.
- Prefer small, self-contained changes that can be reviewed independently.
- Preserve existing behavior unless the task explicitly requires changing it.
- Inspect relevant existing code before implementing a new pattern.

## Tests

- Add or update tests when behavior changes.
- Prefer testing observable behavior over implementation details.
- Do not change existing tests merely to make an incorrect implementation pass.

## Documentation

- Update documentation when commands, configuration, APIs, or operational procedures change.
- Do not duplicate detailed task-specific instructions in this file.
- Keep task-specific plans and investigation notes in separate documents when they are worth preserving.

## Safety

- Do not expose, commit, or hard-code secrets.
- Do not run destructive database or production operations unless explicitly requested.
- Do not claim that a command, test, benchmark, or verification succeeded unless it was actually run successfully.
