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


package edu.duke.cs.osprey.ematrix;

import java.util.HashMap;
import java.util.Map;

import edu.duke.cs.osprey.confspace.RCTuple;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.confspace.SimpleConfSpace.Position;
import edu.duke.cs.osprey.confspace.SimpleConfSpace.ResidueConf;
import edu.duke.cs.osprey.energy.ConfEnergyCalculator;
import edu.duke.cs.osprey.energy.EnergyCalculator;
import java.io.Serializable;

/**
 * This class stores a reference energy for each residue position & AA type
 * (which is the minimum intra-RC energy at that position & AA type)
 */
public class SimpleReferenceEnergies implements Serializable {
	
	// *sigh* this would be soooo much easier if Java supported optional function args...
	public static class Builder {
		
		private SimpleConfSpace confSpace;
		private EnergyCalculator ecalc;
		private boolean addResEntropy = false;
		
		public Builder(SimpleConfSpace confSpace, EnergyCalculator ecalc) {
			this.confSpace = confSpace;
			this.ecalc = ecalc;
		}
		
		public Builder addResEntropy(boolean val) {
			addResEntropy = val;
			return this;
		}
		
		public SimpleReferenceEnergies build() {
			ConfEnergyCalculator confEcalc = new ConfEnergyCalculator.Builder(confSpace, ecalc)
				.addResEntropy(addResEntropy)
				.build();
			return new SimplerEnergyMatrixCalculator.Builder(confEcalc)
				.build()
				.calcReferenceEnergies();
		}
	}
	
	private Map<String,Double> energies;
	
	public SimpleReferenceEnergies() {
		energies = new HashMap<>();
	}
	
	public Double get(int pos, String resType) {
		return energies.get(makeKey(pos, resType));
	}
	
	public void set(int pos, String resType, double val) {
		energies.put(makeKey(pos, resType), val);
	}
	
	private String makeKey(int pos, String resType) {
		return "" + pos + "-" + resType;
	}
	
	public double getOffset(SimpleConfSpace confSpace, int pos, int rc) {
		String resType = confSpace.positions.get(pos).resConfs.get(rc).template.name;
		return getOffset(pos, resType);
	}
	
	public double getOffset(int pos, String resType) {
		
		double energy = get(pos, resType);
		
		if (Double.isFinite(energy)) {
			
			// NOTE: negate reference energies here, so they can be added later like normal energy offsets
			return -energy;
			
		} else {
			// if all RCs for a residue type have infinite one-body energy
			// (i.e., are impossible),
			// then they stay at infinity after eRef correction
			return Double.POSITIVE_INFINITY;
		}
	}
	
	public double getFragmentEnergy(SimpleConfSpace confSpace, RCTuple frag) {
		double energy = 0;
		for (int i=0; i<frag.size(); i++) {
			Position pos = confSpace.positions.get(frag.pos.get(i));
			ResidueConf rc = pos.resConfs.get(frag.RCs.get(i));
			energy += getOffset(pos.index, rc.template.name);
		}
		return energy;
	}
}

