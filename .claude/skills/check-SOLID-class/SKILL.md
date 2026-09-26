---
name: check-SOLID-class
description: Reviews a single Java class in this codebase (src/main/java) against this project's SOLID principles, weighted the way CLAUDE.md defines them - Single Responsibility is the dominant check. Use when asked to check/review/audit a specific class for SOLID compliance, "does this class do too much", or before/after refactoring a large or tangled class.
model: claude-3-5-opus # or claude-3-5-sonnet, claude-3-opus, etc.
context: fork # Optional: runs the skill in an isolated subagent
---

## Input

Takes a class as an argument: a fully-qualified name (`fr.an.projectanalysis.jira.service.JiraIssueService`), a simple name (`JiraIssueService`), or a file path. If none is given, ask the user which class to check, or use the class most recently discussed/edited in the conversation.

Resolve it to a file:
- Simple/fully-qualified name → `find src/main/java -name '<SimpleName>.java'` (drop the package prefix, Java filenames are the simple name).
- Path → use as-is.

Read the whole file before judging anything - do not review from a partial read or from memory of the class.

## How this project weighs SOLID

This is this project's own rubric (from `CLAUDE.md`), not generic textbook SOLID - apply it with this weighting, not evenly:

> Also follow SOLID architecture principle: S=Single (most important), O=Open-Close, L=Liskov, I=Interface segregation (not important when using a single springboot @Service class implementation), D=Dependency. Every class should do only 1 thing, and delegate to others if too complex.

Concretely:
- **S (Single Responsibility) - the check that matters.** Spend most of your effort here (Step 1 below).
- **O (Open/Closed) - light touch.** Only flag a real, already-visible extension point that's hard-coded (see Step 2).
- **L (Liskov) - only if relevant.** Only applies when the class extends/implements something; most classes here don't need this check.
- **I (Interface Segregation) - usually skip.** CLAUDE.md explicitly downgrades this "when using a single springboot @Service class implementation" - the common case in this codebase (`@Service`, `@Component`, `@RestController`, each with exactly one implementation). Only flag if the class implements/exposes an interface with methods unrelated callers are forced to depend on.
- **D (Dependency) - quick check.** Constructor injection of collaborators, not field `@Autowired`, not `new SomeService()` for something that should be injected.

## Step 1: Single Responsibility - identify every responsibility

Go through the class method by method (and field by field) and classify what *kind* of work each one does. This codebase already has a clear division of kinds/layers - use it as the classification scheme:

- **Persistence / on-disk layout** (reading, writing, partitioning, caching) → belongs in a `*Repository` class.
- **Query filtering / criteria matching** for one DTO-shaped filter → belongs in its own `*Criteria implements Predicate<T>` class (see `GitHubPrCriteria`, `JiraIssueCriteria`, `MailMessageCriteria`), built from a `*CriteriaDTO`.
- **Generic matching helpers** (CSV "contains any", date-range, availability yes/no/any, token-range, regex) reused by more than one criteria class → belongs in `fr.an.projectanalysis.util.CritUtils`, not duplicated per class.
- **Source-to-DTO mapping** (raw API/JSON shape → this app's flattened DTO) → belongs in a `*Mapper` class.
- **HTTP/REST shaping** (`@RequestParam`/`@RequestBody`/`@PathVariable` handling, response wrapping) → belongs in a `*RestController`, delegating the actual work to its `*Service`.
- **Domain orchestration specific to this service** (e.g. walking partitions to find a "nearby" issue, aggregating per-user stats) → fine to live in the `*Service` itself; this is its actual single responsibility, as long as it isn't re-implementing filtering/mapping/persistence that belongs in one of the layers above.

List every responsibility found, each with the concrete methods/fields that implement it.

- **1 responsibility** (plus constructor/field plumbing and small private helpers that only exist to support that one responsibility) → passes.
- **2+ responsibilities**, especially ones that duplicate another layer's job (a service doing its own file I/O, a controller doing filtering logic, a criteria class also doing persistence) → violation.

## Step 2: rate each extra responsibility and propose a concrete extraction

For every responsibility beyond the first:
- Name the extraction target using the vocabulary above - reuse an existing class if one already fits this concern elsewhere in the codebase, otherwise name the new class precisely (`XxxCriteria`, `XxxMapper`, an addition to `CritUtils`, or a new focused class if none of those fit).
- List the exact methods/fields to move.
- Note what the extracted class needs from the original (constructor args, or none if the moved methods are already static/pure).

**Don't manufacture busywork.** Do not recommend extracting:
- A handful of small private helpers (rough guide: well under 30 lines total) that exist only to support the class's one responsibility and are only called from 1-2 methods of that same responsibility.
- Something purely because the class is long - LOC is not a SOLID violation by itself. A long class doing genuinely one thing (e.g. a big but single-purpose partitioned repository) passes Step 1. Size-only concerns belong to the sibling skill `/list-big-classes`, not this one.

This matches this project's own instructions: "Don't add features, refactor, or introduce abstractions beyond what the task requires... Three similar lines is better than a premature abstraction."

## Step 3: O / L / I / D - quick checks

- **Open/Closed**: is there a recurring "kind" (a new filter field, a new DTO variant, a new enum bucket) that requires editing a long if/else or switch inside this class each time one is added, when a pluggable/table-driven approach is clearly warranted? Only flag if this pattern is already visibly repeating (e.g. a type/resolution "bucket" switch) - don't invent a hypothetical future extension.
- **Liskov**: only check if the class `extends`/`implements` something (e.g. a `Predicate<T>`, an abstract `*ChangeRecord`, a Spring interface). Confirm overridden/implemented methods honor the supertype's contract (don't narrow accepted inputs, don't throw where the contract doesn't, `Predicate.test` behaves consistently for the documented null case).
- **Interface Segregation**: skip for a plain single-implementation `@Service`/`@Component`/`@RestController` (the default here, per CLAUDE.md). Only flag a genuinely fat interface forcing callers to depend on methods they don't use.
- **Dependency**: collaborators arrive via constructor injection (this codebase's consistent style); flag field-level `@Autowired` or a `new XxxService()`/`new XxxRepository()` where injection should be used instead. The class should depend on the repository/service abstraction already provided, not reach around it (raw file I/O, raw HTTP, another layer's internals).

## Step 4: report

Give a compact report, in this order:
1. **Verdict**: PASS or NEEDS-REFACTOR (S is almost always the deciding factor; O/L/I/D rarely flip the verdict on their own).
2. **Responsibilities found** (numbered, from Step 1).
3. **Suggested extractions**, one per extra responsibility (from Step 2), each naming the target class and the methods to move.
4. **O/L/I/D notes**, only if something was actually flagged in Step 3 - say "no other SOLID issues found" otherwise rather than padding the report.

This skill only reports findings - do not apply the refactor unless the user explicitly asks for it afterwards. If asked to apply it, follow this project's standing constraints: don't run `mvn`/`ng` yourself, don't add tests, make the minimal edit that achieves the split, and let the user recompile.
