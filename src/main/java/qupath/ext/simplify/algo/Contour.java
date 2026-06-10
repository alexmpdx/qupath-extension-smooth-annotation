package qupath.ext.simplify.algo;

import java.util.List;

/**
 * A single contour (ring or open polyline) extracted from a ROI.
 *
 * @param points the ordered vertices; for a closed contour the first point is
 *               <i>not</i> repeated at the end
 * @param closed whether the contour forms a closed loop (a polygon ring) or an
 *               open polyline
 */
public record Contour(List<Vec> points, boolean closed) {
}
