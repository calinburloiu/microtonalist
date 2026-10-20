We are working on issue #383, the architecture for proper threading for the application by using Apache Pekko and migrating the UI to JavaFX. Read the following documents:

- `issues/00383-arch-threading-and-ui/requirements.md`, which covers most of the task
- `issues/00383-arch-threading-and-ui/track-node-proposal.md`
- `issues/00383-arch-threading-and-ui/2026-10-20-gap-decisions.md`, which records the decisions that close the open gaps (threading layer, track threads, layered MIDI graph) and takes precedence over `requirements.md` and `track-node-proposal.md`.

Also explore other things mentioned in those documents: issues, the PoC (with code and documents) etc.

Use /superpowers:brainstorming to decide together the architecture and to write a design doc.

Deliverables:

- The design doc with the end-to-end architecture that covers threading and UI.
- Sub-issues created for #90 and #99 that each should generally map to a plan document with a PR.
- Update issue #310 which is outdated w.r.t. to the gaps closed in `2026-10-20-gap-decisions.md`. You may also consider closing or updating its sub-issues that don't contradict the new architecture, but bear in mind that the MIDI graph is out of scope in this session. The grooming and of #310 and its sub-issues should happen in a future session.
