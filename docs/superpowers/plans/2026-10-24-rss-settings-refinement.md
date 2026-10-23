# RSS Settings Refinement

Approved scope: order Data Management as Summary, RSS, Clear Image Cache; simplify the queue line; group Import Mode and Max saved items in the editor; default new subscriptions to30; label saved-card values; add a persistent Enabled checkbox; show errors without attempt/success timestamps.

Disabled subscriptions skip scheduled/manual acquisition and stale ingestion but keep saved articles and pending AI tasks. Existing retention settings remain unchanged. Add Room8-to9 migration with enabled=true for existing subscriptions. Keep v0.5.9/code40 for the same PR.

1. Add regression coverage for defaults, disabled acquisition/ingestion, and migration preservation.
2. Implement persistence, repository/scheduler filtering, and labeled UI changes.
3. Verify JVM tests, Android migration/ingestion/UI tests, APK build/install, and screenshots.
4. Synchronize specifications and task history; commit locally without pushing.

Implementation and independent review completed. Verification covers the new default, disabled acquisition/stale writes, retained AI tasks, v8 upgrade preserving retention100, editor groups, card labels, checkbox interaction, and absence of timestamps. Same PR version remains v0.5.9/code40.

Final checks: 203 JVM tests and 45 Android tests pass; debug/test APK builds pass. Updated app installed with existing data retained; actual saved-card labels, retention100 preservation, enabled state, and small queue text inspected in the screenshot.
