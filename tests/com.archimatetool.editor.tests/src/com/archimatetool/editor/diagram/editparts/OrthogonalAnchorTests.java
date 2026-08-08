/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package com.archimatetool.editor.diagram.editparts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.RectangleFigure;
import org.eclipse.draw2d.RoundedRectangle;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.gef.Request;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for a bug in OrthogonalAnchor#getLocation(Point) where a
 * reference point positioned in a figure's interior -- away from any edge,
 * and not the figure's exact center -- silently collapses to the figure's
 * center instead of being ray-cast to the boundary.
 * <p>
 * Root cause: the position-classification switch in getLocation() only
 * defines explicit cases for reference points on or near the figure's edges
 * (the 16 LEFT/RIGHT/TOP/BOTTOM/_CORNER combinations). Any position that
 * classifies as MIDDLE|CENTER (and, for rounded figures, the *_CORNER-interior
 * combinations) falls through to the switch's {@code default} branch, which
 * unconditionally returns {@code figureBBox.getCenter()} -- even though the
 * reference point is not at the center and a well-defined boundary crossing
 * exists.
 * <p>
 * This is the same rendering behaviour independently identified by @mwith
 * while implementing PR #1242 (import support for sourceAttachment /
 * targetAttachment on issue #1237), but scoped to a different trigger path:
 * #1242's fix only adjusts newly-generated bendpoints on XML import. These
 * tests exercise the underlying anchor calculation directly -- the path left
 * untouched by that fix, and the one reachable through ordinary interactive
 * editing (dragging a bendpoint, or moving/resizing a shape onto one).
 * <p>
 * For context on the intended, standard behaviour this anchor is meant to
 * approximate: OrthogonalAnchor extends {@link org.eclipse.draw2d.ChopboxAnchor},
 * whose own API documentation states that its location is found by
 * calculating where a line from the owner figure's center through the
 * reference point intersects the box -- i.e. a ray-cast to the boundary is
 * the documented, intended behaviour for every reference point, not just
 * ones near an edge. See:
 * https://help.eclipse.org/latest/topic/org.eclipse.draw2d.doc.isv/reference/api/org/eclipse/draw2d/ChopboxAnchor.html
 *
 * <p><b>Note:</b> these tests were written to match this project's existing
 * conventions and are believed correct, but have not been compiled or run
 * against the real Eclipse/GEF/Draw2D binaries -- only against a standalone
 * extraction of the same classification/switch logic, run independently.
 * Please verify locally before relying on them in CI.
 */
public class OrthogonalAnchorTests {

    /**
     * Builds a bare, unparented figure with the given bounds -- enough to
     * drive OrthogonalAnchor#getLocation() without needing a full EditPart
     * or diagram model.
     */
    private RectangleFigure createFigure(int x, int y, int width, int height) {
        RectangleFigure figure = new RectangleFigure();
        figure.setBounds(new Rectangle(x, y, width, height));
        return figure;
    }

    /**
     * Constructs an OrthogonalAnchor using a plain (non-Create/Reconnect)
     * Request, so fAnchorType stays unset and updateRemoteFig() never
     * populates a remote figure. This isolates the position-classification
     * switch under test from the separate "reference point inside a REMOTE
     * figure's center" adjustment, which is a different code path.
     */
    private OrthogonalAnchor createAnchor(IFigure figure) {
        return new OrthogonalAnchor(figure, new Request(), true);
    }

    @Test
    public void testReferenceOutsideBox_AnchorsAtBoundary_SanityCheck() {
        RectangleFigure figure = createFigure(564, 312, 162, 93);
        OrthogonalAnchor anchor = createAnchor(figure);

        Point result = anchor.getLocation(new Point(750, 450));
        Point center = figure.getBounds().getCenter();

        assertNotEquals(center, result,
                "Sanity check: a reference point outside the box should already anchor away from center today");
    }

    @Test
    public void testReferenceInsideBox_OffCenter_DoesNotCollapseToCenter() {
        // Bounds close to the real repro data used in the issue writeup
        RectangleFigure figure = createFigure(564, 312, 162, 93);
        OrthogonalAnchor anchor = createAnchor(figure);

        // Clearly inside the figure's interior, well away from both the
        // center point and any edge -- this is the case that reproduces
        // the bug. On unpatched code, this assertion fails: getLocation()
        // returns the figure's center regardless of where inside the box
        // the reference point actually sits.
        Point reference = new Point(590, 320);
        Point result = anchor.getLocation(reference);
        Point center = figure.getBounds().getCenter();

        assertNotEquals(center, result,
                "A bendpoint inside the shape's interior, not at its exact center, "
                + "must not silently anchor at the shape's center");
    }

    @Test
    public void testReferenceInsideBox_ResultLiesOnFigureBoundary() {
        RectangleFigure figure = createFigure(564, 312, 162, 93);
        OrthogonalAnchor anchor = createAnchor(figure);

        Point reference = new Point(590, 320);
        Point result = anchor.getLocation(reference);
        Rectangle box = figure.getBounds();

        assertTrue(isOnBoundary(box, result),
                "The anchor point for an interior, off-center reference should land "
                + "exactly on the figure's edge -- the same documented behaviour "
                + "ChopboxAnchor guarantees for every non-center reference point");
    }

    @Test
    public void testReferenceExactlyAtCenter_CenterIsTheOnlyLegitimateFallback() {
        RectangleFigure figure = createFigure(564, 312, 162, 93);
        OrthogonalAnchor anchor = createAnchor(figure);

        Point center = figure.getBounds().getCenter();
        Point result = anchor.getLocation(center.getCopy());

        // This is the one input with no well-defined ray direction, so
        // returning center here is correct today and after any fix.
        assertEquals(center, result,
                "When the reference point IS the figure's center, center is the only sane fallback");
    }

    @Test
    public void testRoundedRectangleInteriorCornerBand_AlsoCollapsesToCenter() {
        // Rounded-corner figures (typical ArchiMate elements) expose a wider
        // interior region prone to the same bug: the *_CORNER-interior
        // classification combinations are equally undefined in the switch.
        // getCornerDimension() recognises org.eclipse.draw2d.RoundedRectangle
        // directly, so that's used here rather than RectangleFigure.
        RoundedRectangle figure = new RoundedRectangle();
        figure.setBounds(new Rectangle(100, 100, 200, 120));
        figure.setCornerDimensions(new Dimension(12, 12));
        OrthogonalAnchor anchor = createAnchor(figure);

        Point reference = new Point(105, 105);
        Point result = anchor.getLocation(reference);
        Point center = figure.getBounds().getCenter();

        assertFalse(result.equals(center),
                "Rounded-rectangle elements are also affected -- not just plain rectangles");
    }

    /**
     * A point is "on the boundary" of an axis-aligned rectangle if it lies on
     * one of the four edges: x equals the left/right edge with y in range, or
     * y equals the top/bottom edge with x in range.
     */
    private boolean isOnBoundary(Rectangle box, Point p) {
        boolean onVerticalEdge = (p.x == box.x || p.x == box.x + box.width)
                && p.y >= box.y && p.y <= box.y + box.height;
        boolean onHorizontalEdge = (p.y == box.y || p.y == box.y + box.height)
                && p.x >= box.x && p.x <= box.x + box.width;
        return onVerticalEdge || onHorizontalEdge;
    }
}
