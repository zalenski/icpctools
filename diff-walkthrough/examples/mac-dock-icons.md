# Mac dock icons

The desktop tools were missing their dock icon on macOS. `Shell.setImage` now has to run on the SWT UI thread, so each tool posts that call with `Display.asyncExec`. The image is still loaded on the calling thread. The runnable checks that the shell has not already been disposed.

## Balloon Utility

`BalloonUtility/src/org/icpc/tools/balloon/BalloonUtility.java:1109-1113`

The PNG is decoded into an `Image`, then `setImage` is posted to the display thread. The window title is set on the calling thread, on the line just above this block, so it does not wait for the runnable.

## Presentation Admin

`PresAdmin/src/org/icpc/tools/presentation/admin/internal/Admin.java:115-118`

Admin already keeps its icon in `ImageResource`. Only `setImage` moves onto the UI thread, which is why this site has no local `Image` variable.

## Problem Set Editor

`ProblemSet/src/org/icpc/tools/contest/util/problemset/ProblemSetEditor.java:569-573`

Same order as Balloon Utility: the PNG is decoded first, then `setImage` is posted. Building the editor continues on the next line and does not wait for the icon.

## Award Utility

`Resolver/src/org/icpc/tools/resolver/awards/AwardUI.java:731-735`

The resolver icon follows the same pattern. The separate taskbar icon earlier in the method is unchanged; this block is only the shell image macOS shows in the dock.

## Guard before touching the shell

The runnable can run after the window is already gone. Each site returns when the shell has been disposed:

- `BalloonUtility/src/org/icpc/tools/balloon/BalloonUtility.java:1111-1112`
- `Resolver/src/org/icpc/tools/resolver/awards/AwardUI.java:733-734`

Presentation Admin and the Problem Set Editor use the same check around their own `setImage` call.
