We are working on the architecture for proper threading for the application by using Apache Pekko and to migrate the UI to JavaFX, read `issues/00383-arch-threading-and-ui/requirements.md` for the requirements and `issues/00383-arch-threading-and-ui/track-node-proposal.md` for the track handling proposal.

The purpose of this session is to close a few gaps before proceeding to the end-to-end design.

## Open gaps

1. Do we want to isolate Apache Pekko by abstracting the threading communication?
    - Leaving a door open to replace Apache Pekko in the future with something else is a soft goal, a nice to have.
    - A harder goal is to allow a future extension of the UI / Business Actor communication with sequential consistency. So an abstraction layer would provide room for that.
    - Another advantage is to provide room in this abstraction layer for the subscription mechanism to dispatch messages internally in the Business Thread.
    - We don't want this abstraction layer to be a naive mirror of Apache Pekko, it should real add extra value.
    - The alternative to this abstraction layer is to simply send messages to the Business Actor from service classes, for example from `TuningService`. Of course, we still need something at the receive end, but the size of the layer is much smaller.
    - Do we want this abstraction layer to be explicit in the build at the module level? If we want this, than modules like `tuner` do not depend (directly) on Apache Pekko, but instead depend on a module that provides the abstraction layer interface. Then, there can be another module that provides the concrete implementation via Apache Pekko.
2. How further do we want to go with this abstraction layer for threading communication? Is it only for the communication between UI and Business Actors? Or should it be generalized for the communication with the Track Actor?
    - If we do not want to extend it to Track Actors, does the proposal from `track-node-proposal.md` look good?
3. Refactoring the MIDI graph is out of scope now, this is covered by issue #310. But it's worth thinking a bit about this now to open the gates for that refactoring and make it easier.
    - So the questions are:
        - (a) Do we want an expanded MIDI graph that connects devices directly with the track pipeline (no sub-graphs)?
        - (b) Or do we want a layered graph with an outer graph of tracks and devices and sub-graphs for each track that contains track pipelines?
    - I incline towards (b) and my proposal from `track-node-proposal.md`. But if we completely isolate the three concerns (rig domain model, rig MIDI graph and track threads communication), then when working with the MIDI graph you don't care about threads/actors, this is abstracted away.
