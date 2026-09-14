# arch-boundary-repro

Minimal reproduction for a SonarQube defect: **the architecture analysis report is silently
discarded when an exit point's SQL statement exceeds 255 characters.**

Three methods, no dependencies. `shortQuery` is the control at 49 characters of SQL; `longQuery`
and `createTable` carry 604 and 413, past the limit.

```bash
mvn package
# then scan to sonarcloud.io or a local SonarQube Server
```

The scan reports `ANALYSIS SUCCESSFUL` and `BUILD SUCCESS`, and the project appears normally in the
UI with its current architecture populated — but it lists **no exit points**, and the scanner log
carries a single `WARNING`:

```
Failed to send the architecture analysis report: Error 500 on .../architecture/analysis
```

Shorten every statement to 243 characters or fewer, rescan, and all three exit points appear.

**[Full write-up in BUG.md](BUG.md)** — root cause, the measured threshold on production, three
separable defects, and the alternative causes that were eliminated.

Affects SonarQube Cloud production and SonarQube Server 2026.5.0.131240. Verified 14 September 2026.
