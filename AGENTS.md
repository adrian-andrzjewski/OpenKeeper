# Repository Guidelines

## Project Structure & Module Organization

The application is Java code under `src/main/java/toniarts/openkeeper/`, organized by domain packages such as `game`, `view`, and `tools`. Tests live in `src/test/java/toniarts/openkeeper/` and generally follow the same package layout. The `assets/` directory is the Gradle `:assets` subproject and supplies runtime resources; its `Converted/` directory is excluded from resources. Gradle configuration and the checked-in wrapper are at the repository root.

## Build, Test, and Development Commands

Use the wrapper and Java 25 toolchain configured by the build (Gradle 9.8.1):

- `./gradlew build` — compile and assemble the application and assets.
- `./gradlew test` — run the JUnit test suite.
- `./gradlew run` — launch OpenKeeper.
- `./gradlew modelViewer` — launch the model viewer utility.

On Windows, use `.\gradlew.bat` with the same task names. Playing and developing the game requires original Dungeon Keeper II files; keep personal game installations out of commits.

## Coding Style & Naming Conventions

Follow the existing Java style and the README's default NetBeans formatter guidance. Organize imports, keep global variables near the top of their class, add Javadocs to public and protected methods, and include the project license header from `license.txt` in new source files. Use lowercase package names, `UpperCamelCase` class names, and descriptive test names ending in `Test`.

## Testing Guidelines

Tests use JUnit Jupiter and `useJUnitPlatform()`. Put tests under the matching `src/test/java` package and name classes `*Test.java`. Add focused tests for changed behavior and run `./gradlew test`; use `./gradlew build` to check full compilation and packaging.

## Commit & Pull Request Guidelines

Recent commits use concise sentence-style subjects and often append an issue number, for example `Research in the research order with its research points (#683)`. Keep one feature per branch and commit. Pull requests should explain the change, link a related issue when available, report relevant build or test results, and include screenshots for visible UI or gameplay changes.
