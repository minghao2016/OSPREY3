package edu.duke.cs.osprey.energy;

import java.util.ArrayList;
import java.util.List;

import edu.duke.cs.osprey.confspace.ConfSearch.EnergiedConf;
import edu.duke.cs.osprey.confspace.ConfSearch.ScoredConf;
import edu.duke.cs.osprey.confspace.ParametricMolecule;
import edu.duke.cs.osprey.confspace.RCTuple;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.ematrix.SimpleReferenceEnergies;
import edu.duke.cs.osprey.minimization.ObjectiveFunction.DofBounds;
import edu.duke.cs.osprey.parallelism.TaskExecutor;
import edu.duke.cs.osprey.parallelism.TaskExecutor.TaskListener;
import edu.duke.cs.osprey.tools.Progress;

/**
 * Calculate energy for molecules created from conformation spaces.
 * 
 * Provides support for applying conformation energy modifications,
 * such as reference energies, residue entropies, and energy partitions. 
 */
public class ConfEnergyCalculator {
	
	// TODO: residue entropies, integrate with epart
	
	public static class Builder {
		
		private SimpleConfSpace confSpace;
		private EnergyCalculator ecalc;
		
		/**
		 * How energies should be partitioned among single and pair fragments.
		 */
		private EnergyPartition epart = EnergyPartition.Traditional;
		
		private SimpleReferenceEnergies eref = null;
		
		public Builder(SimpleConfSpace confSpace, EnergyCalculator ecalc) {
			this.confSpace  = confSpace;
			this.ecalc = ecalc;
		}
		
		public Builder setEnergyPartition(EnergyPartition val) {
			this.epart = val;
			return this;
		}
		
		public Builder setReferenceEnergies(SimpleReferenceEnergies val) {
			this.eref = val;
			return this;
		}
		
		public ConfEnergyCalculator build() {
			return new ConfEnergyCalculator(confSpace, ecalc, epart, eref);
		}
	}
	
	public final SimpleConfSpace confSpace;
	public final EnergyCalculator ecalc;
	public final EnergyPartition epart;
	public final SimpleReferenceEnergies eref;
	public final TaskExecutor tasks;
	
	private ConfEnergyCalculator(SimpleConfSpace confSpace, EnergyCalculator ecalc, EnergyPartition epart, SimpleReferenceEnergies eref) {
		this.confSpace = confSpace;
		this.ecalc = ecalc;
		this.epart = epart;
		this.eref = eref;
		this.tasks = ecalc.tasks;
	}
	
	/**
	 * Calculate the energy of a molecule fragment generated from a conformation space using residue interactions generated by the energy partition.
	 * 
	 * @param frag The assignments of the conformation space 
	 * @return The energy of the resulting molecule fragment
	 */
	public double calcEnergy(RCTuple frag) {
		return calcEnergy(frag, EnergyPartition.makeFragment(confSpace, eref, frag));
	}
	
	/**
	 * Calculate the energy of a molecule fragment generated from a conformation space.
	 * 
	 * @param frag The assignments of the conformation space 
	 * @param inters The residue interactions
	 * @return The energy of the resulting molecule fragment
	 */
	public double calcEnergy(RCTuple frag, ResidueInteractions inters) {
		ParametricMolecule pmol = confSpace.makeMolecule(frag);
		DofBounds bounds = confSpace.makeBounds(frag);
		return ecalc.calcEnergy(pmol, bounds, inters);
	}
	
	/**
	 * Asynchronous version of {@link #calcEnergy(RCTuple,ResidueInteractions)}.
	 * 
	 * @param frag The assignments of the conformation space 
	 * @param inters The residue interactions
	 * @param listener Callback function that will receive the energy. Called on a listener thread which is separate from the calling thread.
	 */
	public void calcEnergyAsync(RCTuple frag, ResidueInteractions inters, TaskListener<Double> listener) {
		ecalc.tasks.submit(() -> calcEnergy(frag, inters), listener);
	}
	
	/**
	 * Calculate energy of a scored conformation. Residue interactions are generated from the energy partition.
	 * 
	 * @param conf The conformation to analyze
	 * @return The conformation with attached energy
	 */
	public EnergiedConf calcEnergy(ScoredConf conf) {
		return new EnergiedConf(conf, calcEnergy(new RCTuple(conf.getAssignments())));
	}

	/**
	 * Asynchronous version of {@link #calcEnergy(ScoredConf)}.
	 * 
	 * @param conf The conformation to analyze
	 * @param listener Callback function that will receive the energy. Called on a listener thread which is separate from the calling thread.
	 */
	public void calcEnergyAsync(ScoredConf conf, TaskListener<EnergiedConf> listener) {
		ecalc.tasks.submit(() -> calcEnergy(conf), listener);
	}

	public List<EnergiedConf> calcAllEnergies(List<ScoredConf> confs) {
		return calcAllEnergies(confs, false);
	}
	
	public List<EnergiedConf> calcAllEnergies(List<ScoredConf> confs, boolean reportProgress) {
		
		// allocate space to hold the minimized values
		List<EnergiedConf> econfs = new ArrayList<>(confs.size());
		for (int i=0; i<confs.size(); i++) {
			econfs.add(null);
		}
		
		// track progress if desired
		final Progress progress;
		if (reportProgress) {
			progress = new Progress(confs.size());
		} else {
			progress = null;
		}
		
		// minimize them all
		for (int i=0; i<confs.size(); i++) {
			
			// capture i for the closure below
			final int fi = i;
			
			calcEnergyAsync(confs.get(i), (econf) -> {
				
				// save the minimized energy
				econfs.set(fi, econf);
				
				// update progress if needed
				if (progress != null) {
					progress.incrementProgress();
				}
			});
		}
		ecalc.tasks.waitForFinish();
		
		return econfs;
	}
}
