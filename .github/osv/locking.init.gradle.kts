/*
 * CI-ONLY Gradle init script for the dependency-audit gate (.github/workflows/dependency-audit.yml).
 *
 * It enables Gradle dependency locking on the two APK modules and registers a task that resolves
 * their runtime classpaths so `--write-locks` can emit a `gradle.lockfile` per module. Those
 * lockfiles pin the FULL transitive graph that actually ships (every library module + external
 * dep: libadb-android, spake2, conscrypt, bouncycastle, AndroidX, …) and are what OSV-Scanner
 * reads to fail the build on a known advisory.
 *
 * This is applied ONLY via `--init-script` in CI. It never touches the committed build scripts,
 * the lockfiles are generated transiently and never committed, and the normal build never sees
 * locking — so a regression here cannot affect a real build. See ADR-003 §5 / CLAUDE.md.
 */
gradle.allprojects {
    val proj = this
    if (proj.name == "app-recv" || proj.name == "app-send") {
        proj.dependencyLocking {
            lockAllConfigurations()
        }
        proj.tasks.register("osvResolveForLocks") {
            // --write-locks is incompatible with the configuration cache, and we resolve at
            // execution time; opt this task out explicitly (the workflow also passes
            // --no-configuration-cache). Mirrors the Gradle-docs `resolveAndLockAll` pattern,
            // scoped to the runtime classpaths (everything that ships).
            notCompatibleWithConfigurationCache("Resolves configurations at execution time")
            doLast {
                proj.configurations
                    .filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath") }
                    // Force GRAPH resolution only (selected component versions) — NOT artifact
                    // resolution. Dependency locking records the graph, and on Android a raw
                    // `resolve()` of a *RuntimeClasspath fails with AAR variant-selection
                    // ambiguity (android-aar-metadata / android-classes-jar / …). Touching
                    // resolutionResult.root resolves the graph, writes the lock, and never asks
                    // for artifact files, so the ambiguity never arises.
                    .forEach { it.incoming.resolutionResult.root }
            }
        }
    }
}
