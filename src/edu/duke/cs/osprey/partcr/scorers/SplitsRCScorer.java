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

package edu.duke.cs.osprey.partcr.scorers;

import edu.duke.cs.osprey.confspace.RC;
import edu.duke.cs.osprey.partcr.SplitWorld;

public class SplitsRCScorer implements RCScorer {
	
	private double splitPenalty;
	
	public SplitsRCScorer() {
		this(1);
	}
	
	public SplitsRCScorer(double splitPenalty) {
		this.splitPenalty = splitPenalty;
	}
	
	@Override
	public double calcScore(SplitWorld splitWorld, RC rc, double boundErr) {
		
		int numVoxels = splitWorld.getSplits().getRCInfo(rc).getNumVoxels();
		return boundErr/(splitPenalty*(numVoxels - 1) + 1);
	}
}
