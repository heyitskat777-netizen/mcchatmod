# Identity denials — alpha.10

Target: Fabric / Minecraft 1.21.11 / Java 21. Keep an offline copy of your whole
config/lotc-journal folder before upgrading. Existing characters schema 1 receives
a paired backup before schema 2; archive schema remains 9.

1. Open Characters → Review links. Choose two different unlinked characters and
   click **Deny link / keep separate**. Confirm should become unavailable; history
   should identify the kept-separate pair and offer **Clear selected denial**.
2. Close/reopen, resize and restart Minecraft. Re-select that pair in either
   direction. It should still be denied. Check the names stay selected after resize.
3. Select the denial in history and clear it. No positive link should appear.
   Explicitly confirm a link, then undo it. Deny should be available again.
4. Deny A/B, then link A to another unlinked identity C. Linking C to B must also
   be blocked. Clear the original denial explicitly to permit such linking.
5. Check original transcripts, stars, aliases, active persona and assigned memories
   remain unchanged. Restore a complete paired backup in a separate test profile
   and verify its saved denials, including whether a denial was active or cleared.

This completes the negative identity-decision control. Global correction of
automatic message tags, combined related-dialogue views for confirmed links, scene
grouping and AI/extraction remain separate milestones.
