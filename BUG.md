# Bug: architecture analysis report is discarded when an exit point's SQL exceeds 255 characters

**Reported by:** Chris Chedgey · 14 September 2026
**Reproduction:** this repository — see [`QueryRunner.java`](src/main/java/com/example/QueryRunner.java)
**Affects:** SonarQube Cloud **production** (sonarcloud.io, verified live) and SonarQube Server
**2026.5.0.131240** (LTA candidate). Architecture plugins `3.3 (build 12101)`.
**Severity:** high — silently removes *all* exit points and entry points for an affected project, and therefore every cross-project relationship. Current architecture still populates, so the project looks healthy.

## Summary

The architecture analyser builds an exit-point boundary key from the **full text of the SQL
statement** it finds, prefixed by a short kind marker. The column that key is stored in,
`arch_boundaries.boundary_key`, is `varchar(255)`. The prefix is `SQL query:*:` (12 characters) for
a query and `SQL exec:*:` (11) for a statement execution, so the exact limits are **243 characters
for a query and 244 for an exec**. Anything longer overflows the column and the insert is rejected.

Because the inserts are batched, one oversized key **rolls back every boundary for the project** —
not just the offending one.

Current architecture survives: the structure graph is stored by a different path and is unaffected.
What is lost is every **boundary** — all exit points and entry points, and therefore every
cross-project relationship. That combination is what makes the failure so hard to spot: the
Architecture view populates and looks healthy, and the absence of exit points reads as "nothing to
discover here" rather than as an error.

The scanner reports this as a `WARNING` and still finishes with `ANALYSIS SUCCESSFUL` and
`BUILD SUCCESS`, so from the user's side the scan appears to have worked and the Architecture view
is simply empty.

## Impact

Long SQL statements are entirely ordinary in data-engineering codebases. This was found while
scanning Apache SeaTunnel, where it fired on two independent statements — one in test
configuration, one in main code — and excluding the first module simply surfaced the second.

The consequence is not a missing edge but a missing boundary set: no exit points, no entry points,
and therefore no cross-project relationships for that project. Since cross-project dependency
discovery is built entirely on these boundaries, the feature cannot work at all on a project
containing one long static query.

**Confirmed independently on a third instance.** A colleague scanning the same project to a
separate SonarQube Cloud instance reports exactly this signature: current architecture viewable,
no exit points reported. He had taken that as "this project has no exit points" rather than as a
failure — which is the clearest possible demonstration of the visibility problem below. Measured
here on SonarQube Server, the same project has a 111-node structure graph and zero rows in
`arch_boundaries`.

## Reproduction

This repository *is* the reproduction: three methods, no dependencies. Build it so compiled
classes are present, though the defect reproduces with or without them.

```bash
mvn package
```

```java
public ResultSet shortQuery(Connection connection) throws SQLException {
    Statement statement = connection.createStatement();
    return statement.executeQuery("SELECT id, name FROM customer WHERE active = true");
}

public ResultSet longQuery(Connection connection) throws SQLException {
    Statement statement = connection.createStatement();
    return statement.executeQuery(
        "SELECT c.id, c.name, c.email, c.created_at, o.order_id, o.total_amount, "
      + ... /* 604 characters in total */ );
}
```

1. `mvn package` then scan to sonarcloud.io (or a local SQS).
2. Observe in the scanner output:

```
[INFO]    Sensor JavaArchitectureSensor [architecture] (done) | time=147ms
[INFO]    ANALYSIS SUCCESSFUL, you can find the results at: https://sonarcloud.io/...
[WARNING] Failed to send the architecture analysis report:
          Error 500 on https://api.sonarcloud.io/architecture/analysis : {"message":"Internal Server error"}
[INFO]    BUILD SUCCESS
```

3. Shorten every SQL statement to 243 characters or fewer, rebuild, rescan: the warning disappears.

### Controlled comparison — same project, build, scanner and instance (sonarcloud.io prod)

With statements of 604 and 413 characters, the run logs one *"Failed to send the architecture
report"*. With those same statements shortened to 49, 73 and 71 characters, it logs none.
`JavaArchitectureSensor` ran in both cases, so the difference is not a skipped sensor. Statement
length is the only variable.

### The threshold on production is exactly 255

The boundary key is a short kind prefix followed by the statement text, so a `varchar(255)` column
predicts a different statement limit per prefix. Both were measured against sonarcloud.io:

For a **query**, prefix `SQL query:*:` at 12 characters: a 240-character statement gives a key of
252 and is accepted; 243 characters gives a key of exactly 255 and is accepted; 244 characters
gives 256 and is rejected.

For an **exec**, prefix `SQL exec:*:` at 11 characters: 244 characters gives a key of 255 and is
accepted; 245 characters gives 256 and is rejected.

Two independent prefixes, each flipping on a single character, both at exactly the same key length
— which also shows the limit applies to the key rather than to the statement. This rules out an
intermittent server error and confirms that production enforces the same 255-character limit, not
merely a similar symptom.

### What the user sees

Scanning the same project twice, once with the SQL over the limit and once under, gives two
projects that differ only in that one number:

- the under-limit project lists its three exit points as expected
- the over-limit project lists none

Neither page reports a problem. The over-limit project is scanned, its measures are present, and
its current architecture is populated — it simply has no exit points, exactly as a project with no
SQL would look. Nothing in the UI distinguishes "we found none" from "we received none".

## Root cause

From the SonarQube Server log (the cloud returns only `Internal Server error`):

```
ERROR web[...] Unhandled Exception - ### Error committing transaction.
Cause: org.apache.ibatis.executor.BatchExecutorException:
  ...insert (batch index #2) failed. 1 prior sub executor(s) completed successfully,
  but will be rolled back.
Cause: java.sql.BatchUpdateException: Batch entry 7 insert into arch_boundaries
  (organization_id, project_id, branch_id, boundary_key, direction, created_at)
  values (..., 'SQL exec:*:CREATE TABLE IF NOT EXISTS audit_event (  event_id BIGSERIAL
  PRIMARY KEY, ...', 'EXIT_POINT', ...)
  was aborted: ERROR: value too long for type character varying(255)
```

Schema:

`boundary_key` is declared `character varying(255)`, not null. It is also part of the table's
primary key, `pk_arch_boundaries`, a btree over `(organization_id, project_id, branch_id,
boundary_key, direction)` — so it is an indexed column, which is worth bearing in mind when
choosing the fix.

## Three separable defects

1. **No length guard.** The producer emits a key of unbounded length; the consumer's column is
   fixed at 255. Truncating or hashing at the point the key is built would prevent it. Note the key
   derives from arbitrary user source text, so no realistic column width makes this safe on its own.
2. **Blast radius far exceeds the fault.** A single bad row discards every boundary in the batch.
   Even with a length guard, one malformed boundary should not cost a project its entire model.
3. **The failure is invisible where it matters.** `WARNING` in scanner output, then
   `ANALYSIS SUCCESSFUL` and `BUILD SUCCESS`, exit code 0. In CI nobody sees it. Worse, because
   current architecture still populates, the UI looks healthy and simply shows no exit points —
   indistinguishable from a project that genuinely has none. This is not hypothetical: a colleague
   on the feature's own team reached exactly that wrong conclusion about this project before we
   compared notes.

## Ruled out

Each of these was a plausible cause and was eliminated:

- **Scanning without compiled classes.** Reproduced with a full `mvn package` build, binaries
  present. (First seen on a source-only scan, which is why it was checked.)
- **PostgreSQL.** `varchar(255)` comes from SonarQube's own schema, which is database-agnostic.
  Also reproduced on SonarQube Cloud.
- **Scanner version skew.** The scanner downloads its analysers from the server
  (`Load/download plugins`), so producer and consumer ship together. Reproduced with both
  SonarScanner CLI 8.1.0.6389 and sonar-maven-plugin (4.0.0.4121 against SQS, 5.1.0.4751 against
  cloud).
- **Project size or Apache-specific content.** Reproduced on a three-method synthetic project.

## Not a regression in the LTA candidate

Verified against **sonarcloud.io production**, where it fails identically and at exactly the same
255-character threshold. This is a live defect, not something introduced in 2026.5.0 — which raises the question of why it has not been reported
before, and whether cross-project discovery has yet been exercised against a codebase containing
long SQL.

## Suggested fix

Bound the key where it is produced, and make the storage layer resilient to a single bad row.
A stable hash of the full statement with a readable prefix would keep keys both bounded and
recognisable in the UI — and note that the UI currently shows these raw keys to users, which is a
separate usability point already raised from the cross-project session.
