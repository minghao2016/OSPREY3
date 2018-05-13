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

package edu.duke.cs.osprey.astar.conf.order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.duke.cs.osprey.astar.conf.ConfIndex;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.astar.conf.scoring.AStarScorer;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;

public class StaticEnergyHMeanAStarOrder implements AStarOrder {
	
	// NOTE: the Dynamic A* paper says this is pretty good
	// but either I'm doing something wrong here,
	// or my test cases are really ill-conditioned
	// StaticScoreHMeanAStarOrder performs waaaay better!
	// use that one instead
	
	private EnergyMatrix emat;
	private List<Integer> posOrder;
	
	public StaticEnergyHMeanAStarOrder(EnergyMatrix emat) {
		this.emat = emat;
		this.posOrder = null;
	}
	
	@Override
	public void setScorers(AStarScorer gscorer, AStarScorer hscorer) {
		// nothing to do
	}

	@Override
	public int getNextPos(ConfIndex confIndex, RCs rcs) {
		if (posOrder == null) {
			posOrder = calcPosOrder(confIndex, rcs);
		}
		return posOrder.get(confIndex.node.getLevel());
	}

	private List<Integer> calcPosOrder(ConfIndex confIndex, RCs rcs) {
		
		// init permutation array
		List<Integer> order = new ArrayList<>();
		for (int pos=0; pos<rcs.getNumPos(); pos++) {
			order.add(pos);
		}
		
		// calculate all the scores
		List<Double> scores = new ArrayList<>();
		for (int pos=0; pos<rcs.getNumPos(); pos++) {
			scores.add(scorePos(rcs, pos));
		}
		
		// sort positions in order of decreasing score
		Collections.sort(order, new Comparator<Integer>() {

			@Override
			public int compare(Integer pos1, Integer pos2) {
				double score1 = scores.get(pos1);
				double score2 = scores.get(pos2);
				// NOTE: use reverse order for decreasing sort
				return Double.compare(score2, score1);
			}
		});
			
		return order;
	}

	private double scorePos(RCs rcs, int pos1) {
		
		double score = 0;
		
		for (int pos2=0; pos2<rcs.getNumPos(); pos2++) {
			
			if (pos2 == pos1) {
				continue;
			}
		
			// first, find the min pairwise energy over all rc pairs
			double minPairwise = Double.POSITIVE_INFINITY;
			for (int rc1 : rcs.get(pos1)) {
				for (int rc2 : rcs.get(pos2)) {
					minPairwise = Math.min(minPairwise, emat.getPairwise(pos1, rc1, pos2, rc2));
				}
			}
			
			// compute the harmonic average of normalized pairwise energies
			double pos2Score = 0;
			for (int rc1 : rcs.get(pos1)) {
				for (int rc2 : rcs.get(pos2)) {
					double normalizedPairwise = emat.getPairwise(pos1, rc1, pos2, rc2) - minPairwise;
					if (normalizedPairwise != 0) {
						pos2Score += 1.0/normalizedPairwise;
					}
				}
			}
			int numRcs1 = rcs.get(pos1).length;
			int numRcs2 = rcs.get(pos2).length;
			pos2Score = (numRcs1*numRcs2 - 1)/pos2Score;
			
			score += pos2Score;
		}
		
		return score;
	}
}
