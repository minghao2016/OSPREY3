/*
** This file is part of OSPREY.
** 
** OSPREY is free software: you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation, either version 2 of the License, or
** (at your option) any later version.
** 
** OSPREY is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
** 
** You should have received a copy of the GNU General Public License
** along with OSPREY.  If not, see <http://www.gnu.org/licenses/>.
*/

package edu.duke.cs.osprey.astar.conf;

import edu.duke.cs.osprey.tools.MathTools;
import edu.duke.cs.osprey.tools.UnpossibleError;

public interface ConfAStarNode extends Comparable<ConfAStarNode> {

	ConfAStarNode assign(int pos, int rc);
	double getGScore();
	void setGScore(double val);
	double getHScore();
	void setHScore(double val);
	int getLevel();
	void getConf(int[] conf);
	void index(ConfIndex index);
	
	default int[] makeConf(int numPos) {
		int[] conf = new int[numPos];
		getConf(conf);
		return conf;
	}
	
	default double getScore() {
		return getGScore() + getHScore();
	}

	@Override
	default int compareTo(ConfAStarNode other) {
		return Double.compare(getScore(), other.getScore());
	}

	default double getGScore(MathTools.Optimizer optimizer) {
		return Tools.optimizeScore(getGScore(), optimizer);
	}
	default void setGScore(double val, MathTools.Optimizer optimizer) {
		setGScore(Tools.optimizeScore(val, optimizer));
	}

	default double getHScore(MathTools.Optimizer optimizer) {
		return Tools.optimizeScore(getHScore(), optimizer);
	}
	default void setHScore(double val, MathTools.Optimizer optimizer) {
		setHScore(Tools.optimizeScore(val, optimizer));
	}

	default double getScore(MathTools.Optimizer optimizer) {
		return Tools.optimizeScore(getScore(), optimizer);
	}

	public static class Tools {

		public static double optimizeScore(double score, MathTools.Optimizer optimizer) {
			switch (optimizer) {
				case Minimize: return score; // the pq is naturally a min-heap
				case Maximize: return -score; // negate the score so the pq acts like a max-heap
				default: throw new UnpossibleError();
			}
		}
	}
}
