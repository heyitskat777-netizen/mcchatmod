# Alpha.6 user check — favorites and direct transcripts

Use Minecraft 1.21.11, Fabric API and Java 21. Keep a backup before upgrading.
Older partial parser policies still require their matching older mod to finish;
alpha.6 uses the same policy 3 as alpha.5, with an additive backed-up schema 6.

1. Press your journal key, then **View transcripts** directly. Before importing
   anything, an empty view should be safe; Done returns to the main journal.
2. Import a known finished log. Click a message, then **Favorite selected**, or
   click its `[ ]` marker. The marker becomes `[*]`. Star one of two identical
   messages and verify the other remains separate. With the pane focused, F toggles
   the selected visible message; offscreen selections cannot be toggled accidentally.
3. Choose **Favorites: Only**. Combine source/channel/search, including Roleplay only
   and UNKNOWN review. Remove the last star: the empty result should stay usable.
   Return to **Favorites: All**. Test scrolling, resizing and selecting another row.
4. Leave a star, close/reopen the journal and restart Minecraft. It should persist.
   Create a backup after starring; restore a copy only while Minecraft is closed,
   keeping your working database safe, and confirm the captured star state.
5. If testing batch undo, star a newly imported message and an older one first.
   Undo the new batch. Only its owned message/star should disappear. Repeat import
   must not resurrect undone records; the older favorite must remain.

Report build/version and source/line references instead of private dialogue.
Live capture, scene grouping, identity assignment and item/name/task extraction
are scheduled later and are not enabled by this build.
