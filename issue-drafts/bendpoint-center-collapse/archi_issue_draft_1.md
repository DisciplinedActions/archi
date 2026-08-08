# A bendpoint inside a shape's bounds silently collapses that connection's anchor to the shape's center after any anchor recompute (e.g. moving the shape)

## Summary

If a connection's bendpoint ends up positioned inside one of its own connected shapes' bounds, the connection continues to render normally (anchored at the shape's boundary) until something triggers an anchor recompute — moving the shape is sufficient. After that, the connection's touch-point on that shape silently snaps to the shape's **center**, regardless of the connection's original routing, and stays locked there no matter how far past center the offending bendpoint is subsequently dragged. No error, warning, or visual indication is given either when the bendpoint first lands inside the shape, or when the anchor collapses to center.

This is a real, reproducible correctness/robustness issue, not a cosmetic one — it silently changes a diagram's routing in a way that isn't reflected anywhere in the UI until you notice the line looks wrong.

## Relationship to #1237 and #1242

This is the same underlying rendering behavior independently identified by @mwith while implementing #1242 (import support for `sourceAttachment`/`targetAttachment`). From that PR:

> "if a bend point is located on the edge of the element then the drawing algorithm will render the connection from the center of the element to the attachment point... So in order not to make changes to the drawing algorithm then the attachment points will be adjusted so the start and end points is offset by 1."

#1242's fix (merged) addresses this specifically for the **XML import path** — newly-generated bendpoints from an Exchange Format file are now positioned to avoid the trap. That's real, tested, and this issue isn't asking to revisit it.

This issue is about the path #1242 explicitly left alone: **live interactive editing.** A bendpoint can end up inside a shape's bounds through ordinary UI actions — dragging a bendpoint directly, or moving/resizing a shape so its bounds grow to swallow a bendpoint that was previously in valid open space — with no XML import involved at all. #1242 fenced off one entry point to this bug; the underlying mechanism is still reachable through others, exactly as mwith's own words above suggest was a known, deliberate scope decision rather than an oversight.

## Steps to reproduce

1. Create two shapes (any type) on a View, positioned with some diagonal separation.
2. Draw a connection between them (any relationship type). Confirm it anchors normally, at each shape's boundary.
3. Select the connection and drag a bendpoint onto it, then drag that bendpoint so it sits clearly *inside* one of the two shapes' bounds. At this point the connection still renders normally — anchored at the boundary, unaffected.
4. Move either shape (a simple drag is enough).
5. Observe: the connection's touch-point on the shape with the in-bounds bendpoint has moved to that shape's center. The originally correct boundary anchoring is gone.
6. Optional: continue dragging the offending bendpoint further inside the shape, well past its center. The anchor remains locked at the shape's center regardless of how far past it the bendpoint is moved.

## Evidence

Tested against a real `.archimate` model, comparing exported coordinates directly against expected geometry:

- **Before the shape move:** connection anchored at the shape's boundary corner, matching the no-bendpoint baseline exactly.
- **After the shape move:** shape bounds `x=564, y=312, w=162, h=93` → computed center `(645, 358.5)`. The connection's rendered touch-point on that shape visually matches this center point (~48%/43% across the shape's width/height in the screenshot, i.e. within normal estimation error of dead center), not the boundary.
- A second, independent connection between the same two shapes, with a *different* bendpoint also placed inside the same shape, exhibited the identical center-anchor behavior — ruling out a coincidence specific to one connection or one bendpoint position.
- Deliberately dragging the offending bendpoint well past the shape's center (specifically to test whether the anchor tracks distance) produced no change — the anchor remained locked at center.

## Why this matters beyond cosmetics

- **It's silent.** Nothing in the UI indicates a bendpoint is in an invalid position, either when it's placed there or when the anchor subsequently collapses.
- **It's triggered by extremely ordinary operations.** Moving a shape, or dragging a bendpoint, are two of the most common actions in diagram editing — this isn't an edge case reached only through unusual workflows.
- **It can be reached without ever directly dragging a bendpoint into a shape.** A shape resize or reposition can just as easily move a shape's bounds *onto* a bendpoint that was previously in valid open space, with the identical silent consequence.
- **The XML import path is now covered by #1242 — this issue is specifically about what that fix didn't touch.** Newly-imported bendpoints from an Exchange Format file are positioned defensively to avoid the trap. Nothing prevents an author from later dragging a bendpoint (imported or hand-drawn) into a shape, or moving a shape onto one, at any point after that.

## Precedent

The Draw2D `ChopboxAnchor` class — part of the same GEF/Draw2D lineage Archi's diagram editor is built on — defines the standard algorithm for exactly this problem. From the official API documentation:

> "The ChopboxAnchor's location is found by calculating the intersection of a line drawn from the center point of its owner's box to a reference point on that box... returns the Point where a line from the center of the Rectangle to the Point reference intersects the Rectangle."

This calculation is well-defined for *any* reference point other than one that coincides exactly with the box's center — inside the box or outside it makes no difference to the math; a ray from the center through any off-center point, extended outward, always crosses the boundary at a determinate point. There is no basis in the standard algorithm for "reference point is inside the shape" to be treated as an error condition or to trigger a fallback to the center.

**Update — root cause confirmed in the actual source:** per Phillipus's own comment on #1237, Archi's default anchor is not plain `ChopboxAnchor` but a custom `OrthogonalAnchor` (written by @jbsarrodie), which "uses bendpoints to position the connection" — `ChopboxAnchor` is only used if that preference is turned off. `OrthogonalAnchor` extends `ChopboxAnchor` directly (`com.archimatetool.editor.diagram.editparts.OrthogonalAnchor`, in `com.archimatetool.editor`), and its `getLocation(Point reference)` override is the exact site of the bug.

The method classifies `reference` into a 3×3 grid relative to the owner figure's bounds — `LEFT`/`LEFT_CORNER`/`MIDDLE`/`RIGHT_CORNER`/`RIGHT` on the X axis, `TOP`/`TOP_CORNER`/`CENTER`/`BOTTOM_CORNER`/`BOTTOM` on the Y axis — then switches on the combination. Only the 16 combinations touching an edge or corner are given explicit cases. `MIDDLE | CENTER` — reference point in the interior on both axes, i.e. exactly the "bendpoint dragged inside the shape" scenario this issue describes — has no case, and falls through to:

```java
default:
    return figureBBox.getCenter();
```

For plain (non-rounded) rectangles this is the only unhandled combination, since the `*_CORNER` bands are zero-width when `corner.width`/`corner.height` are `0`. For rounded-rectangle figures (the typical ArchiMate element shape), the corner bands are non-zero, so the gap is wider: `LEFT_CORNER|TOP_CORNER`, `MIDDLE|TOP_CORNER`, `RIGHT_CORNER|TOP_CORNER`, `LEFT_CORNER|CENTER`, `RIGHT_CORNER|CENTER`, `LEFT_CORNER|BOTTOM_CORNER`, `MIDDLE|BOTTOM_CORNER`, and `RIGHT_CORNER|BOTTOM_CORNER` are all equally unhandled, in addition to `MIDDLE|CENTER`.

This is a missing switch case, not a flaw in the underlying ray-cast math — every other combination in the same switch already does the correct edge/corner-aware geometry; only the interior combinations were never given a case.

**Also worth weighing before proposing a fix here:** while reviewing #1242, Phillipus found that bendpoints placed very close to a connection's ends caused separate, zoom-level-dependent rendering problems — tested explicitly with `OrthogonalAnchor` on and with `ChopboxAnchor` (orthogonal off) for comparison. That's real, hands-on-verified fragility in this exact area of the code, from the person who'd actually know. It doesn't contradict what's confirmed in this issue, but it's a sign the anchor/bendpoint rendering path has more than one sharp edge, and a fix here should be tested carefully against that finding, not assumed to be clean.

## Proposed fix

**Status: root cause located and confirmed against the real source; fix logic verified by an independent standalone reproduction (see Verification below); not yet compiled or run against the actual Eclipse/GEF/Draw2D binaries.** Given that #1242 was deliberately scoped to avoid touching the drawing algorithm, and Phillipus's own zoom-instability finding nearby, this should still get real GEF-level testing (including at multiple zoom levels) before being trusted as a merge-ready patch — but it's no longer a speculative precedent-only sketch.

Minimal fix: in `OrthogonalAnchor.getLocation()`, replace the switch's unconditional `default: return figureBBox.getCenter();` with a ray-cast from the box's true center through `reference` to the boundary, reserving center only for the one genuinely undefined input — `reference` exactly coinciding with center.

```java
/**
 * Computes the point where a ray from the box's center through `reference`
 * exits the box. Well-defined for any reference point except one that
 * coincides exactly with the center -- inside-the-box and outside-the-box
 * reference points are handled identically; there is no reason to special-
 * case "inside" as its own condition.
 */
Point getBoundaryIntersection(Rectangle box, Point reference) {
    double cx = box.x + box.width / 2.0;
    double cy = box.y + box.height / 2.0;
    double hw = box.width / 2.0;
    double hh = box.height / 2.0;

    double dx = reference.x - cx;
    double dy = reference.y - cy;

    if (dx == 0 && dy == 0) {
        // The ONLY legitimate fallback case: reference IS the center, no
        // direction to project. Implementer's choice how to handle this --
        // e.g. default to a fixed side, or fall back to center here
        // specifically (not for any other input).
        return FALLBACK_POINT;
    }

    double scale;
    if (dx == 0) {
        scale = hh / Math.abs(dy);
    } else if (dy == 0) {
        scale = hw / Math.abs(dx);
    } else {
        scale = Math.min(hw / Math.abs(dx), hh / Math.abs(dy));
    }

    return new Point(cx + dx * scale, cy + dy * scale);
}
```

## Verification

Both the buggy `default: return figureBBox.getCenter();` branch and the ray-cast replacement above were extracted verbatim (classification logic and switch unchanged) into a standalone Java reproduction and run independently of Archi's build, using coordinates matching the real repro data below. Results:

- The bug reproduces exactly as described: a reference point at `(590, 320)` inside shape bounds `(564, 312, 162, 93)` classifies as `MIDDLE|CENTER` and the original logic returns `(645, 358.5)` — the shape's exact center — discarding the reference point's actual position.
- The ray-cast replacement returns `(578.57, 312.0)` for the same input, and this point was verified to lie exactly on the shape's top edge (`y = 312`, `x` within `[564, 726]`) — a real, geometrically correct boundary crossing, not merely "a point other than center."
- The same held for a rounded-rectangle figure (corner arc `12,12`, the typical ArchiMate element shape): a reference point in the interior corner band (`LEFT_CORNER|TOP_CORNER`, one of the additional combinations rounded shapes expose) also collapsed to center under the original logic and correctly resolved to the left edge under the fix.
- The one input where both versions agree, by design: the reference point exactly at the shape's center has no defined ray direction, and both the original and fixed logic correctly return center for that case only.

A JUnit test file (`OrthogonalAnchorTests.java`) covering these same scenarios, written to match this project's existing test conventions (package, license header, JUnit 5 style, suite registration), is included alongside this issue. It has not yet been compiled or run against the real Eclipse/GEF/Draw2D binaries — only the standalone extraction above has actually been executed — so please verify it locally before relying on it in CI.

## Test cases

**1. Normal case — reference outside the box (sanity check, should already pass today):**
Box `(0,0,100,50)`, reference `(150,100)` → expected boundary point `(83.3, 50)` (exits through the bottom edge, since the reference direction is proportionally steeper than the box's own aspect ratio).

**2. Regression case — reference inside the box, not at center (this is the actual bug):**
Box `(0,0,100,50)`, reference `(70,30)` → expected boundary point `(100, 37.5)` (exits through the right edge). Archi's current behavior returns `(50, 25)` (the center) instead — this is the test that should fail today and pass after the fix.

**3. True degenerate case — reference exactly at center:**
Box `(0,0,100,50)`, reference `(50,25)` → no well-defined direction; this is the only input where a fallback is appropriate, and the only one on which today's behavior and the fixed behavior should agree.

**4. Real-world case, from actual reproduction data (see attached `.archimate` files):**
Source shape bounds `(546,304,120,55)` — center `(606, 331.5)`. A bendpoint confirmed inside this shape's bounds at `(576,309)` (verified via the model's own relative-bendpoint coordinates, cross-checked from both the source and target sides of the connection). Expected boundary point per the algorithm above: `(569.3, 304)` — on the top edge, near the top-left corner, consistent with the connection's original routing direction before the bug manifested. This is the concrete case this investigation was built around; happy to provide the full `.archimate` files as attachments if useful for automated test coverage.
