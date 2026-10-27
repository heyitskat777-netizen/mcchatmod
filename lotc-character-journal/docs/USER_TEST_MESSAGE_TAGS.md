# Per-message tags — alpha.11

Target: Fabric / Minecraft 1.21.11 / Java 21. Keep an offline copy of your entire
config/lotc-journal folder before upgrading. Characters schema 1/2 receives a
verified paired backup before upgrading to 3; archive schema stays 9.

1. Open View transcripts, click an individual RP message, then **Review tags**.
   Check its Automatic tags and original message/channel. Search names/aliases,
   click Choose character, and check the UUID suffix when names are duplicated.
2. Select an existing tag with the Tag button. Choose the corrected identity/kind
   and **Replace**; use **Add tag** for another mention or **Remove** to reject
   just the selected tag. Resize before saving and check the whole draft remains.
3. **Save reviewed tags**, reopen, restart and check Related dialogue for both the
   original and corrected characters. Save stores the exact list shown. Later
   automatic updates remain hidden until you explicitly restore automatic tags.
4. **Reject all tags**. It should show Reviewed with zero saved tags. Reimport the
   original log or edit an alias, reopen and restart: tags must remain rejected.
   **Restore automatic** should bring back current suggestions. It does not restore
   an old cached list or create any persona memory.
5. An UNKNOWN message should allow MENTION only. Check original text/channel, stars,
   active persona and assigned memories stay unchanged after tag review. Identity
   linking/denials are separate decisions.
6. Restore a complete paired backup in a separate profile and check the saved review
   (including reviewed-empty) remains. Undo an imported batch: only that batch's
   owned messages and their tag reviews should disappear.

A failed or stale Save retains the draft and disables further writes until
**Reload saved tags**. Reload deliberately replaces the draft with the newest saved
state. Save accepts at most 64 reviewed tags; it never silently truncates a larger
automatic list. Confirmed-link combined views and scene grouping remain later work.
