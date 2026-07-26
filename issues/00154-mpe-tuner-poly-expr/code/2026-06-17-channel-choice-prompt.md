# Channel Choice Refactoring (Prompt)

Commit `2f4e1de` (PR #235) update the @docs/architecture/tuner/mpe-tuner-paper.md by unifying tie-breaking rules for both allocation and channel freeing. The special rules for picking a channel to free were also refined. The steps for channel allocation were improved. Check the PR description for details as well as the git diffs.

Your task is to update the code from @scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala and perform an audit of it. Use /superpowers:brainstorming .

For tie-breaking `minBy` is used and that's an excellent idea. But this now needs to be used both for `bestCandidate` and for `freeChannel` methods. Or maybe `freeChannel` needs to use `bestCandidate`.

The class' tests need to be reviewed and updated. Make sure there are test cases for all branches from the new mermaid diagram from the paper.

Some tests that are currently ignored from `MpeTunerTest` may need to be enabled (they currently fail - red, and after the changes they should pass, green). You should identify them. They were added with these changes and future others in mind.

The following are some issues with the current code. If they still make sense after applying the discussed changes make sure you address them.
* In `MpeChannelAllocator`
    - method `doAllocate`: factor out the dropping logic due to high expression pitch bend into a separate method.
    - method `freeChannel`:
        - In the current implementation, finding "the highest and lowest pitched notes across all channels" is performed by passing the date three times. Optimize to do it in one pass with a classic for and two variables for highest and lowest notes. The code could be factored out in a separate method to make the code more clean.
