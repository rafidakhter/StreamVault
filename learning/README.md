# StreamVault performance learnings

Open [the eight-page learning PDF](StreamVault-performance-learnings.pdf).

The document covers the November 4, 2026 Sony BRAVIA investigation: playback memory exhaustion, library memory reductions, parsed-library caching, organization profiling, and persistent movie/series catalogs. It includes architecture diagrams, observed before/after measurements, a debugging playbook, and an interview explanation.

## Evidence and scope

- Raw logs, memory reports, and the diagnostic heap are in the ignored `local-test-data/` folder. Do not commit those captures; they may contain credentials.
- Some later readings were captured directly through ADB during the session and are summarized in the PDF rather than saved as separate logs.
- Results are observations from one TV and manual scenarios, not universal performance guarantees.
- No automated app tests were run. Verification used release builds, manual device navigation/playback, memory samples, timing logs, and a TV restart.
