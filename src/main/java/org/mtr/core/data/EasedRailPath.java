package org.mtr.core.data;

import org.jspecify.annotations.Nullable;
import org.mtr.core.tool.Angle;

/**
 * Horizontal easement (transition curve) geometry for a whole rail — the block-scale
 * counterpart of the real-world cubic parabola and half-wave sine transition curves
 * (三次抛物线 / 半波正弦缓和曲线) used by mainline railways.
 *
 * <p>Instead of the classic abrupt straight-to-arc joins, the entire rail becomes one
 * curvature-continuous path whose curvature starts and ends at zero (so it meets the
 * adjoining rails' ends without a curvature step) and follows the requested profile in
 * between:</p>
 * <ul>
 * <li><b>Cubic parabola</b> — piecewise-<em>linear</em> curvature, so every component of
 * the path is a cubic parabola easement (linear curvature ramp = quadratic heading ramp =
 * cubic lateral offset). The profile ramps up, holds a circular plateau and ramps back
 * down, i.e. the real straight–easement–circle–easement–straight sequence, with the
 * plateau shrinking to a point when the bend is gentle enough for a pure double
 * easement.</li>
 * <li><b>Half-wave sine</b> — a single half-wave of a sine as the curvature diagram, the
 * smoothest of the classic profiles: no corner in the curvature anywhere, not even at
 * the peak.</li>
 * </ul>
 *
 * <p>The heading profile is parameterised as {@code headingChange * profileA(tau) +
 * flex * profileB(tau)} where {@code profileA} carries the mandatory total heading change
 * (zero at the start, one at the end, zero slope at both ends, so the end curvature is
 * zero) and {@code profileB} is a heading-neutral flex bump (zero and zero-slope at both
 * ends, unit peak) supplying whatever extra lateral reach the two nodes demand. The two
 * remaining unknowns — the total arc length and the flex amplitude — are pinned against
 * the two endpoint coordinates with a damped 2×2 Newton iteration.</p>
 *
 * <p>The solved path is integrated once into a uniform sample table (world coordinates
 * plus heading per sample). Rendering, vehicle placement, bounds and radius queries all
 * interpolate that same table, so trains follow the displayed track by construction. All
 * heavy work happens at construction time; per-query cost is a constant-time
 * interpolation. Any geometry the families cannot represent (extreme heading changes,
 * rails doubling back behind their start node, solver failure) returns {@code null} and
 * the caller falls back to the classic segment solver, so eased shapes are always a
 * strictly-optional refinement.</p>
 */
final class EasedRailPath {

	/**
	 * Total arc length of the eased path, in blocks. Always at least the chord length
	 * between the two nodes.
	 */
	final double length;
	/**
	 * Uniform spacing between table samples, in blocks.
	 */
	final double step;
	/**
	 * World X coordinate for each sample; sample {@code i} sits at distance
	 * {@code i * step} from the first node, block-centred.
	 */
	final double[] x;
	/**
	 * World Z coordinate for each sample; sample {@code i} sits at distance
	 * {@code i * step} from the first node, block-centred.
	 */
	final double[] z;
	/**
	 * World heading for each sample, in bearing radians clockwise from east (+X toward
	 * +Z), matching {@link Angle#angleRadians}.
	 */
	final double[] heading;
	/**
	 * Smallest horizontal radius on the first half of the path; 0 when that half is
	 * straight, matching the segment solver's convention.
	 */
	final double minRadius1;
	/**
	 * Smallest horizontal radius on the second half of the path; 0 when that half is
	 * straight.
	 */
	final double minRadius2;

	private static final double MAX_HEADING_CHANGE = Math.toRadians(135);
	private static final double MIN_ALONG = 0.5;
	private static final double STRAIGHT_EPSILON = 1E-9;
	private static final double SOLVE_TOLERANCE = 1E-4;
	private static final int MAX_SOLVE_ITERATIONS = 16;
	private static final int MIN_SOLVE_STEPS = 32;
	private static final int MAX_SOLVE_STEPS = 128;
	private static final double SOLVE_STEP_TARGET = 0.5;
	private static final double FLEX_LIMIT = Math.PI;
	private static final double TABLE_STEP = 0.25;
	private static final int MAX_TABLE_SAMPLES = 1024;
	private static final int MIN_TABLE_SAMPLES = 4;

	private EasedRailPath(double length, double step, double[] x, double[] z, double[] heading, double minRadius1, double minRadius2) {
		this.length = length;
		this.step = step;
		this.x = x;
		this.z = z;
		this.heading = heading;
		this.minRadius1 = minRadius1;
		this.minRadius2 = minRadius2;
	}

	/**
	 * Solves and integrates the eased path between the two nodes.
	 *
	 * @param position1    the first rail node, world block coordinates
	 * @param angle1       the travel heading at the first node
	 * @param position2    the second rail node, world block coordinates
	 * @param angle2       the travel heading at the second node
	 * @param halfWaveSine {@code true} for the half-wave sine profile, {@code false} for
	 *                     the cubic parabola profile
	 * @return the sampled path, or {@code null} when the geometry is outside what the
	 * families can represent (the caller then falls back to the classic segment solver)
	 */
	@Nullable
	static EasedRailPath build(Position position1, Angle angle1, Position position2, Angle angle2, boolean halfWaveSine) {
		final double originX = position1.getX() + 0.5;
		final double originZ = position1.getZ() + 0.5;
		final double cos1 = angle1.cos;
		final double sin1 = angle1.sin;
		final double differenceX = position2.getX() - position1.getX();
		final double differenceZ = position2.getZ() - position1.getZ();
		// TSC stores angle1 facing from node 1 toward node 2, but angle2 facing BACK
		// from node 2 toward node 1 (the Rail.getAngles normalisation applied when rails
		// are created). The eased path needs the travel heading at node 2 - the opposite
		// of the stored angle2. The classic segment solver performs the same correction
		// internally via getOpposite() before solving; without it here, every stored rail
		// computed a heading change near 180 degrees, was rejected by the presolve guard
		// (or, for the geometries that slipped through, solved a hairpin twice as long as
		// the real track) and silently fell back to the classic solver - the "easement
		// curves have no effect" report.
		final Angle angle2Travel = angle2.getOpposite();
		final double headingChange = headingChange(angle1, angle2Travel);

		// Local frame: +t along the first node's heading, +n to its right (bearing +90°).
		final double along = differenceX * cos1 + differenceZ * sin1;
		final double lateral = differenceZ * cos1 - differenceX * sin1;
		final double chord = Math.hypot(along, lateral);

		if (chord < STRAIGHT_EPSILON || along < MIN_ALONG || Math.abs(headingChange) > MAX_HEADING_CHANGE) {
			return null;
		}
		if (Math.abs(headingChange) < STRAIGHT_EPSILON && Math.abs(lateral) < STRAIGHT_EPSILON) {
			return straightPath(originX, originZ, position2.getX() + 0.5, position2.getZ() + 0.5, chord, angle1.angleRadians);
		}

		final double[] solution = solve(halfWaveSine, headingChange, along, lateral, chord);
		if (solution == null) {
			return null;
		}

		return integrate(halfWaveSine, headingChange, solution[0], solution[1], originX, originZ, cos1, sin1, angle1.angleRadians, position2, angle2Travel);
	}

	/**
	 * @return the total heading change between the two node headings, wrapped to
	 * (-180°, 180°] in bearing radians
	 */
	private static double headingChange(Angle angle1, Angle angle2) {
		double headingChange = angle2.angleRadians - angle1.angleRadians;
		while (headingChange > Math.PI) {
			headingChange -= 2 * Math.PI;
		}
		while (headingChange <= -Math.PI) {
			headingChange += 2 * Math.PI;
		}
		return headingChange;
	}

	/**
	 * Damped 2×2 Newton solve for the arc length and the flex amplitude. The total
	 * heading is exact by construction; the two remaining unknowns are pinned by the two
	 * endpoint coordinates. Two attempts are made from different initial guesses before
	 * giving up.
	 */
	@Nullable
	private static double[] solve(boolean halfWaveSine, double headingChange, double along, double lateral, double chord) {
		final double minArcLength = chord + 1E-6;
		final double maxArcLength = chord * 2 + 24;

		double arcLength = chord * 1.05 + 0.1;
		final double[] straightEnd = integrateEnd(halfWaveSine, headingChange, arcLength, 0);
		final double flex = clampFlex((lateral - straightEnd[1]) / Math.max(arcLength / 2, 1E-6));
		final double[] solution = solveFrom(halfWaveSine, headingChange, along, lateral, minArcLength, maxArcLength, arcLength, flex);
		if (solution != null) {
			return solution;
		}

		// Second attempt: estimate the required mid-path heading of the S-bend directly.
		final double arcLengthRetry = chord * 1.25 + 0.5;
		return solveFrom(halfWaveSine, headingChange, along, lateral, minArcLength, maxArcLength, arcLengthRetry, clampFlex(2 * Math.atan2(lateral, along)));
	}

	private static double clampFlex(double flex) {
		return Math.max(-FLEX_LIMIT, Math.min(FLEX_LIMIT, flex));
	}

	/**
	 * One damped Newton run from the supplied initial guess. Returns the converged pair
	 * of arc length and flex amplitude, or {@code null} if it did not settle.
	 */
	@Nullable
	private static double[] solveFrom(boolean halfWaveSine, double headingChange, double along, double lateral, double minArcLength, double maxArcLength, double arcLength, double flex) {
		for (int iteration = 0; iteration < MAX_SOLVE_ITERATIONS; iteration++) {
			final double[] end = integrateEnd(halfWaveSine, headingChange, arcLength, flex);
			final double errorAlong = end[0] - along;
			final double errorLateral = end[1] - lateral;
			if (Math.abs(errorAlong) + Math.abs(errorLateral) < SOLVE_TOLERANCE) {
				return new double[]{arcLength, flex};
			}

			final double stepLength = Math.max(1E-3, arcLength * 1E-5);
			final double stepFlex = 1E-4;
			final double[] endLength = integrateEnd(halfWaveSine, headingChange, arcLength + stepLength, flex);
			final double[] endFlex = integrateEnd(halfWaveSine, headingChange, arcLength, flex + stepFlex);

			final double alongPerLength = (endLength[0] - end[0]) / stepLength;
			final double lateralPerLength = (endLength[1] - end[1]) / stepLength;
			final double alongPerFlex = (endFlex[0] - end[0]) / stepFlex;
			final double lateralPerFlex = (endFlex[1] - end[1]) / stepFlex;

			final double determinant = alongPerLength * lateralPerFlex - alongPerFlex * lateralPerLength;
			if (!Double.isFinite(determinant) || Math.abs(determinant) < 1E-12) {
				return null;
			}

			arcLength = Math.max(minArcLength, Math.min(maxArcLength, arcLength + (errorLateral * alongPerFlex - errorAlong * lateralPerFlex) / determinant));
			flex = clampFlex(flex + (errorAlong * lateralPerLength - errorLateral * alongPerLength) / determinant);
		}

		return null;
	}

	/**
	 * Integrates the heading profile once, at solve resolution, and returns the local
	 * endpoint coordinates (along, lateral) reached by walking {@code arcLength} blocks.
	 */
	private static double[] integrateEnd(boolean halfWaveSine, double headingChange, double arcLength, double flex) {
		final int steps = (int) Math.max(MIN_SOLVE_STEPS, Math.min(MAX_SOLVE_STEPS, Math.ceil(arcLength / SOLVE_STEP_TARGET)));
		final double step = arcLength / steps;
		double along = 0;
		double lateral = 0;

		for (int index = 0; index < steps; index++) {
			final double tau = (index + 0.5) / steps;
			final double theta = headingChange * profileA(tau, halfWaveSine) + flex * profileB(tau, halfWaveSine);
			along += step * Math.cos(theta);
			lateral += step * Math.sin(theta);
		}

		return new double[]{along, lateral};
	}

	/**
	 * Builds the final uniform sample table: midpoint integration at render resolution,
	 * then a smooth (zero-slope at both ends) correction that absorbs the solver residual
	 * so the path lands exactly on both nodes with exactly the requested end headings.
	 */
	private static EasedRailPath integrate(boolean halfWaveSine, double headingChange, double arcLength, double flex, double originX, double originZ, double cos1, double sin1, double heading1, Position position2, Angle angle2) {
		final int sampleCount = (int) Math.max(MIN_TABLE_SAMPLES, Math.min(MAX_TABLE_SAMPLES, Math.ceil(arcLength / TABLE_STEP)));
		final double step = arcLength / sampleCount;
		final double[] x = new double[sampleCount + 1];
		final double[] z = new double[sampleCount + 1];
		final double[] heading = new double[sampleCount + 1];

		x[0] = originX;
		z[0] = originZ;
		heading[0] = heading1;

		double along = 0;
		double lateral = 0;
		double maxCurvature1 = 0;
		double maxCurvature2 = 0;

		for (int index = 1; index <= sampleCount; index++) {
			final double tauMid = (index - 0.5) / sampleCount;
			final double thetaMid = headingChange * profileA(tauMid, halfWaveSine) + flex * profileB(tauMid, halfWaveSine);
			along += step * Math.cos(thetaMid);
			lateral += step * Math.sin(thetaMid);

			final double curvature = (headingChange * slopeA(tauMid, halfWaveSine) + flex * slopeB(tauMid, halfWaveSine)) / arcLength;
			if (index <= sampleCount / 2) {
				maxCurvature1 = Math.max(maxCurvature1, Math.abs(curvature));
			} else {
				maxCurvature2 = Math.max(maxCurvature2, Math.abs(curvature));
			}

			final double tau = (double) index / sampleCount;
			final double theta = headingChange * profileA(tau, halfWaveSine) + flex * profileB(tau, halfWaveSine);
			x[index] = originX + along * cos1 - lateral * sin1;
			z[index] = originZ + along * sin1 + lateral * cos1;
			heading[index] = heading1 + theta;
		}

		// Anchor the path exactly on the second node. The residual is sub-millimetre for
		// ordinary geometry (at most a couple of centimetres for extreme hairpins) and is
		// spread smoothly across the whole path, so no kink is introduced.
		final double residualX = position2.getX() + 0.5 - x[sampleCount];
		final double residualZ = position2.getZ() + 0.5 - z[sampleCount];
		for (int index = 1; index <= sampleCount; index++) {
			final double tau = (double) index / sampleCount;
			final double weight = tau * tau * (3 - 2 * tau);
			x[index] += residualX * weight;
			z[index] += residualZ * weight;
		}

		heading[sampleCount] = angle2.angleRadians;

		return new EasedRailPath(
			arcLength, step, x, z, heading,
			maxCurvature1 > 1E-9 ? 1 / maxCurvature1 : 0,
			maxCurvature2 > 1E-9 ? 1 / maxCurvature2 : 0
		);
	}

	/**
	 * @return a straight sample table for aligned node pairs (zero heading change, zero
	 * lateral offset)
	 */
	private static EasedRailPath straightPath(double originX, double originZ, double targetX, double targetZ, double length, double heading) {
		final int sampleCount = (int) Math.max(MIN_TABLE_SAMPLES, Math.min(MAX_TABLE_SAMPLES, Math.ceil(length / TABLE_STEP)));
		final double step = length / sampleCount;
		final double[] x = new double[sampleCount + 1];
		final double[] z = new double[sampleCount + 1];
		final double[] headingTable = new double[sampleCount + 1];

		for (int index = 0; index <= sampleCount; index++) {
			final double tau = (double) index / sampleCount;
			x[index] = originX + (targetX - originX) * tau;
			z[index] = originZ + (targetZ - originZ) * tau;
			headingTable[index] = heading;
		}

		return new EasedRailPath(length, step, x, z, headingTable, 0, 0);
	}

	/**
	 * Heading ramp carrying the mandatory heading change. Zero at the start, one at the
	 * end, zero slope at both ends, so the curvature starts and ends at zero.
	 */
	private static double profileA(double tau, boolean halfWaveSine) {
		if (halfWaveSine) {
			return (1 - Math.cos(Math.PI * tau)) / 2;
		}
		if (tau <= 0.25) {
			return 8 * tau * tau / 3;
		}
		if (tau <= 0.75) {
			return 4 * tau / 3 - 1D / 6;
		}
		return 1 - 8 * (1 - tau) * (1 - tau) / 3;
	}

	/**
	 * Slope of {@link #profileA}, i.e. the shape of the base curvature diagram times the
	 * arc length: a half-wave sine, or a symmetric trapezoid whose ramps are the linear
	 * curvature climbs of a cubic parabola easement.
	 */
	private static double slopeA(double tau, boolean halfWaveSine) {
		if (halfWaveSine) {
			return Math.PI * Math.sin(Math.PI * tau) / 2;
		}
		if (tau <= 0.25) {
			return 16 * tau / 3;
		}
		if (tau <= 0.75) {
			return 4D / 3;
		}
		return 16 * (1 - tau) / 3;
	}

	/**
	 * Heading-neutral flex bump with unit peak, zero at both ends and zero slope at both
	 * ends. It adds mid-path lateral reach without changing the total heading change.
	 */
	private static double profileB(double tau, boolean halfWaveSine) {
		if (halfWaveSine) {
			return (1 - Math.cos(2 * Math.PI * tau)) / 2;
		}
		if (tau <= 0.25) {
			return 8 * tau * tau;
		}
		if (tau <= 0.75) {
			final double offset = tau - 0.25;
			return 0.5 + 4 * offset - 8 * offset * offset;
		}
		return 8 * (1 - tau) * (1 - tau);
	}

	/**
	 * Slope of {@link #profileB}, i.e. the shape of the flex curvature diagram times the
	 * arc length. Still piecewise linear for the cubic parabola family.
	 */
	private static double slopeB(double tau, boolean halfWaveSine) {
		if (halfWaveSine) {
			return Math.PI * Math.sin(2 * Math.PI * tau);
		}
		if (tau <= 0.25) {
			return 16 * tau;
		}
		if (tau <= 0.75) {
			return 4 - 16 * (tau - 0.25);
		}
		return -16 * (1 - tau);
	}
}
