# Security & supply-chain policy

imini scans its dependencies with [Trivy](https://aquasecurity.github.io/trivy/) and tracks an SBOM. This
note documents the severity policy enforced by `.github/workflows/supply-chain.yml`.

## Severity policy

| Severity | On a pull request / push to main | On the weekly schedule |
|---|---|---|
| **CRITICAL** (with an available fix) | **Blocks the build** (the merge is gated) | Reported only (does not block) |
| CRITICAL (no upstream fix yet) | Reported, not blocked (`ignore-unfixed`) | Reported |
| **HIGH** | Reported to the Security tab, not blocked | Reported to the Security tab |
| MEDIUM / LOW | Not scanned by the gate | Not scanned by the gate |

Rationale: a fixable CRITICAL is actionable now, so it gates merges. HIGH findings are surfaced for triage
without blocking day-to-day work. The weekly run re-scans unchanged dependencies so a CVE disclosed after
a merge still shows up — but it never breaks `main`, because the gate step is skipped on the schedule.

## Where findings appear

- **Security → Code scanning** tab: all HIGH/CRITICAL findings (SARIF upload), every run.
- **PR checks**: the gate fails the `Vulnerability scan (Trivy)` job if a fixable CRITICAL is present.
- **SBOM**: a CycloneDX SBOM artifact (`imini-sbom.cdx.json`) is attached to each run.

## Accepting an exception

If a flagged CVE is not exploitable in imini's context or is an accepted risk, add it to
[`.trivyignore`](../.trivyignore) at the repo root, one CVE ID per line, with a comment explaining why.
Keep the list short and reviewed; entries suppress the finding in the CRITICAL gate.

## Reporting a vulnerability

imini is an education-grade project, not production software (see `docs/WHATS_NOT_INCLUDED.md`). If you
find a security issue, open a GitHub issue describing it, or contact the maintainer privately for anything
sensitive.
