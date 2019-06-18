/*
  Copyright 2006 by Sean Luke and George Mason University
  Licensed under the Academic Free License version 3.0
  See the file "LICENSE" for more information
*/

package sim.app.dheatbugs;

import sim.engine.SimState;
import sim.engine.Steppable;
import sim.field.grid.NDoubleGrid2D;
import sim.util.DoublePoint;

public class DHeatBug implements Steppable {
	private static final long serialVersionUID = 1;

	public int loc_x, loc_y;
	public boolean isFirstStep = true;

	public double idealTemp;
	public double heatOutput;
	public double randomMovementProbability;

	public DHeatBug(double idealTemp, double heatOutput, double randomMovementProbability, int loc_x, int loc_y) {
		this.heatOutput = heatOutput;
		this.idealTemp = idealTemp;
		this.randomMovementProbability = randomMovementProbability;
		this.loc_x = loc_x;
		this.loc_y = loc_y;
	}

	public void addHeat(final NDoubleGrid2D grid, final int x, final int y, final double heat) {
		double new_heat = grid.get(x, y) + heat;
		if (new_heat > DHeatBugs.MAX_HEAT)
			new_heat = DHeatBugs.MAX_HEAT;
		grid.set(x, y, new_heat);
	}

	public void step(final SimState state) {

		DHeatBugs hb = (DHeatBugs) state;

		// Skip addHeat for the first step
		if (!this.isFirstStep) {
			addHeat(hb.valgrid, loc_x, loc_y, heatOutput);
		} else {
			this.isFirstStep = false;
		}

		final int START = -1;
		int bestx = START;
		int besty = 0;

		if (state.random.nextBoolean(randomMovementProbability)) { // go to random place
			bestx = state.random.nextInt(3) - 1 + loc_x;
			besty = state.random.nextInt(3) - 1 + loc_y;
		} else if (hb.valgrid.get(loc_x, loc_y) > idealTemp) { // go to coldest place
			for (int x = -1; x < 2; x++)
				for (int y = -1; y < 2; y++)
					if (!(x == 0 && y == 0)) {
						int xx = (x + loc_x);
						int yy = (y + loc_y);
						if (bestx == START || (hb.valgrid.get(xx, yy) < hb.valgrid.get(bestx, besty))
								|| (hb.valgrid.get(xx, yy) == hb.valgrid.get(bestx, besty)
										&& state.random.nextBoolean())) // not uniform, but enough to break up the
																		// go-up-and-to-the-left syndrome
						{
							bestx = xx;
							besty = yy;
						}
					}
		} else if (hb.valgrid.get(loc_x, loc_y) < idealTemp) { // go to warmest place
			for (int x = -1; x < 2; x++)
				for (int y = -1; y < 2; y++)
					if (!(x == 0 && y == 0)) {
						int xx = (x + loc_x);
						int yy = (y + loc_y);
						if (bestx == START || (hb.valgrid.get(xx, yy) > hb.valgrid.get(bestx, besty))
								|| (hb.valgrid.get(xx, yy) == hb.valgrid.get(bestx, besty)
										&& state.random.nextBoolean())) // not uniform, but enough to break up the
																		// go-up-and-to-the-left syndrome
						{
							bestx = xx;
							besty = yy;
						}
					}
		} else { // stay put
			bestx = loc_x;
			besty = loc_y;
		}

		final int old_x = loc_x;
		final int old_y = loc_y;
		loc_x = hb.valgrid.stx(bestx);
		loc_y = hb.valgrid.sty(besty);

		try {
			int dst = hb.partition.toPartitionId(new int[] { loc_x, loc_y });
			if (dst != hb.partition.getPid()) {
				hb.bugs.remove(old_x, old_y, this);

//				if (!hb.bugs.remove(old_x, old_y, this))
//					System.err.println("Failed to remove!");

				hb.transporter.migrate(this, dst, new DoublePoint(loc_x, loc_y));
				hb.privBugCount--;
			} else {
				hb.bugs.remove(old_x, old_y, this);

//				if (!hb.bugs.remove(old_x, old_y, this))
//					System.err.println("Failed to remove!");

				hb.bugs.add(loc_x, loc_y, this);
				hb.schedule.scheduleOnce(this, 1);
			}
		} catch (Exception e) {
			e.printStackTrace(System.out);
			System.exit(-1);
		}
	}

	public double getIdealTemperature() {
		return idealTemp;
	}

	public void setIdealTemperature(double t) {
		idealTemp = t;
	}

	public double getHeatOutput() {
		return heatOutput;
	}

	public void setHeatOutput(double t) {
		heatOutput = t;
	}

	// public Object domRandomMovementProbability() {
	// return new Interval(0.0, 1.0);
	// }

	public double getRandomMovementProbability() {
		return randomMovementProbability;
	}

	public void setRandomMovementProbability(double t) {
		if (t >= 0 && t <= 1)
			randomMovementProbability = t;
	}

	public String toString() {
		return String.format("[%d, %d]", loc_x, loc_y);
	}
}
