package edu.duke.cs.osprey.multistatekstar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;

/**
 * 
 * @author Adegoke Ojewole (ao68@duke.edu)
 *
 */
@SuppressWarnings("serial")
public class ResidueOrderDynamicScore extends ResidueOrder {

	private enum ScoreType {
		FSCORE,
		HSCORE,
		DISCREPANCY;
	}

	private ArrayList<ArrayList<ArrayList<BigDecimal>>> residueValues;
	private BoltzmannCalculator boltzmann;
	private ScoreType scoreType;
	private int[][][][] RCs;//state,substate,pos,rc

	public ResidueOrderDynamicScore(MSSearchProblem[][] objFcnSearch, String scoreType) {
		super();
		this.boltzmann = new BoltzmannCalculator();
		this.residueValues = allocateResidueValues(objFcnSearch);
		this.RCs = allocateRCArray(objFcnSearch);

		switch(scoreType.toLowerCase()) {
		case "fscore":
			this.scoreType = ScoreType.FSCORE;
			break;
		case "hscore":
			this.scoreType = ScoreType.HSCORE;
			break;
		case "discrepancy":
			this.scoreType = ScoreType.DISCREPANCY;
			break;
		default:
			throw new UnsupportedOperationException("ERROR: unsuported score type: "+scoreType);
		}
	}
	
	private int[][][][] allocateRCArray(MSSearchProblem[][] objFcnSearch) {
		int[][][][] ans = new int[objFcnSearch.length][][][];
		for(int state=0;state<ans.length;++state) {
			ans[state] = new int[objFcnSearch[state].length][][];
			for(int substate=0;substate<ans[state].length;++substate) {
				ans[state][substate] = new int[objFcnSearch[state][substate].pruneMat.getNumPos()][];
				for(int pos=0;pos<ans[state][substate].length;++pos) {
					ans[state][substate][pos] = new int[objFcnSearch[state][substate].pruneMat.unprunedRCsAtPos(pos).size()];
				}
			}
		}
		return ans;
	}

	private ArrayList<ArrayList<ArrayList<BigDecimal>>> allocateResidueValues(MSSearchProblem[][] objFcnSearch) {
		//create a priority queue for each state and substate
		ArrayList<ArrayList<ArrayList<BigDecimal>>> residueValues = new ArrayList<>();
		for(int state=0;state<objFcnSearch.length;++state) {
			residueValues.add(new ArrayList<>());
			for(int subState=0;subState<objFcnSearch[state].length;++subState) {
				residueValues.get(state).add(new ArrayList<>());
				for(int residuePos=0;residuePos<objFcnSearch[state][subState].settings.mutRes.size();++residuePos) {
					residueValues.get(state).get(subState).add(BigDecimal.ZERO);
				}
				residueValues.get(state).get(subState).trimToSize();
			}
			residueValues.get(state).trimToSize();
		}
		residueValues.trimToSize();
		return residueValues;
	}

	protected void setResidueValues(MSSearchProblem[][] objFcnSearch, boolean assigned, boolean parallel) {
		ArrayList<ResisueOrderWorker> workers = new ArrayList<>();
		//set all workers
		for(int state=0;state<objFcnSearch.length;++state) {
			for(int subState=0;subState<objFcnSearch[state].length;++subState) {
				workers.add(new ResisueOrderWorker(objFcnSearch[state][subState], state, subState));
			}
		}

		if(!parallel) {
			for(ResisueOrderWorker w : workers) setResidueValues(w.search, w.state, w.subState, w.search.getPosNums(assigned));
		}
		//execute in parallel
		else {
			workers.parallelStream().forEach(w -> setResidueValues(w.search, w.state, w.subState, w.search.getPosNums(assigned)));
		}
	}

	protected void setResidueValues(MSSearchProblem search, int state, int subState, ArrayList<Integer> positions) {

		int numPos = search.getNumPos();

		for(int pos1 : positions) {
			double pos1Value = 0;
			//also used pruned rcs at pos?
			ArrayList<Integer> pos1RCs = search.pruneMat.unprunedRCsAtPos(pos1);

			for(int pos2=0;pos2<numPos;++pos2) {
				if(pos1==pos2) continue;

				ArrayList<Integer> pos2RCs = search.pruneMat.unprunedRCsAtPos(pos2);

				// first, find the min pairwise energy over all rc pairs
				double minPairwise = Double.POSITIVE_INFINITY;				
				for(int rc1 : pos1RCs) {
					for(int rc2 : pos2RCs) {
						minPairwise = Math.min(minPairwise, search.emat.getPairwise(pos1, rc1, pos2, rc2));
					}
				}

				//compute the harmonic average of normalized pairwise energies
				double pos2Value = 0;
				for(int rc1 : pos1RCs) {
					for(int rc2 : pos2RCs) {
						double normalizedPairwise = search.emat.getPairwise(pos1, rc1, pos2, rc2) - minPairwise;
						if (normalizedPairwise != 0) {
							pos2Value += 1.0/normalizedPairwise;
						}
					}
				}

				pos2Value = (pos1RCs.size()*pos2RCs.size() - 1)/pos2Value;
				if(!Double.isNaN(pos2Value))
					pos1Value += pos2Value;
			}

			if(Double.isNaN(pos1Value)) pos1Value = 0;
			residueValues.get(state).get(subState).set(pos1, (boltzmann.calc(pos1Value)).setScale(64, RoundingMode.HALF_UP));
		}
	}

	private void generatePermutations(MSSearchProblem[] search,
			ArrayList<ArrayList<String>> input, ArrayList<ResidueAssignment> output, 
			ArrayList<String> buf, int depth) {

		if(depth==input.size()) {//each unbound state is assigned
			ArrayList<ArrayList<Integer>> assignments = new ArrayList<>();
			ArrayList<Integer> complex = new ArrayList<>();

			for(int subState=0;subState<depth;++subState) {
				assignments.add(new ArrayList<>());
				assignments.get(subState).add(search[subState].flexRes.indexOf(buf.get(subState)));

				complex.add(search[search.length-1].flexRes.indexOf(buf.get(subState)));
				assignments.get(subState).trimToSize();
			}

			complex.trimToSize();
			assignments.add(complex);
			assignments.trimToSize();

			output.add(new ResidueAssignment(assignments));
			return;
		}

		for(int i=0;i<input.get(depth).size();++i) {
			buf.set(depth, input.get(depth).get(i));
			generatePermutations(search, input, output, buf, depth+1);
		}
	}

	protected ArrayList<ResidueAssignment> getUnboundResidueAssignments(MSSearchProblem[] search) {
		ArrayList<ArrayList<String>> unassignedRes = new ArrayList<>();
		ArrayList<String> buf = new ArrayList<>();
		//get unbound states unassigned flex res
		for(int subState=0;subState<search.length-1;++subState) {
			unassignedRes.add(search[subState].getResidues(false));
			buf.add("");
		}
		unassignedRes.trimToSize();
		buf.trimToSize();

		ArrayList<ResidueAssignment> ans = new ArrayList<>();
		//all n combinations of unbound state, taking 1 residue from each unbound state
		generatePermutations(search, unassignedRes, ans, buf, 0);		
		ans.trimToSize();
		return ans;
	}

	protected ResidueAssignment getBoundResidueAssignments(MSSearchProblem[] search, 
			int splitPos) {

		ArrayList<Integer> complex = new ArrayList<>(); 
		complex.add(splitPos); complex.trimToSize();

		String complexRes = search[search.length-1].flexRes.get(splitPos);

		ArrayList<ArrayList<Integer>> assignments = new ArrayList<>();
		for(int subState=0;subState<search.length-1;++subState) {
			assignments.add(new ArrayList<>());
			int pos = search[subState].flexRes.indexOf(complexRes);
			if(pos != -1) 
				assignments.get(subState).add(pos);
			assignments.get(subState).trimToSize();
		}

		assignments.add(complex); assignments.trimToSize();

		ResidueAssignment ans = new ResidueAssignment(assignments);
		return ans;
	}

	private BigDecimal getStateScoreH(int state, ResidueAssignment assignment) {
		BigDecimal ans = BigDecimal.ONE.setScale(64, RoundingMode.HALF_UP);
		//unbound states
		for(int subState=0;subState<assignment.length()-1;++subState) {
			ArrayList<Integer> unboundPos = assignment.get(subState);
			if(unboundPos.size()==0) continue;
			if(unboundPos.size()>1) throw new RuntimeException("ERROR: unbound state was split into more than one position");
			int pos = unboundPos.get(0);//contains at most one value
			ans = ans.divide(residueValues.get(state).get(subState).get(pos), RoundingMode.HALF_UP);
		}

		//bound state
		int subState = assignment.length()-1;
		ArrayList<Integer> boundPos = assignment.get(subState);
		BigDecimal numerator = BigDecimal.ZERO.setScale(64, RoundingMode.HALF_UP);
		for(int pos : boundPos) {
			numerator = numerator.add(residueValues.get(state).get(subState).get(pos));
		}

		ans = numerator.divide(ans, RoundingMode.HALF_UP);
		return ans;
	}

	private BigDecimal getStateScoreF(int state, ResidueAssignment assignment,
			KStarScore gScore) {
		ArrayList<BigDecimal> stateFScores = new ArrayList<>();

		//iterate through substates
		for(int subState=0;subState<assignment.length();++subState) {
			//get g score from previous assignment (will be 0 if root)
			BigDecimal g = gScore.getPartitionFunction(subState) == null ? 
					BigDecimal.ZERO : gScore.getPartitionFunction(subState).getValues().qstar;
			g = g.setScale(64, RoundingMode.HALF_UP);

			//add h score to gscore
			ArrayList<Integer> unboundPos = assignment.get(subState);
			BigDecimal h = BigDecimal.ZERO.setScale(64, RoundingMode.HALF_UP);
			for(int pos : unboundPos) h = h.add(residueValues.get(state).get(subState).get(pos));

			//add up g and h scores for each substate
			stateFScores.add(g.add(h));
		}

		//then do final division
		BigDecimal denom = BigDecimal.ONE.setScale(64, RoundingMode.HALF_UP);
		for(int subState=0;subState<stateFScores.size()-1;++subState) denom = denom.multiply(stateFScores.get(subState));
		if(denom.compareTo(BigDecimal.ZERO)==0) denom = new BigDecimal("1e-64");

		int complex = stateFScores.size()-1;
		BigDecimal numer = stateFScores.get(complex).setScale(64, RoundingMode.HALF_UP);

		BigDecimal ans = numer.divide(denom, RoundingMode.HALF_UP);
		return ans;
	}

	//includes k* scores from current node
	private BigDecimal getFScore(ResidueAssignment assignment, BigDecimal[] coeffs,
			KStarScore[] gScores) {
		int numStates = coeffs.length;
		BigDecimal ans = BigDecimal.ZERO.setScale(64, RoundingMode.HALF_UP);

		BigDecimal gScore = null;
		if(scoreType == ScoreType.DISCREPANCY)
			gScore = BigDecimal.ZERO.setScale(64, RoundingMode.HALF_UP);

		// get assignment score
		for(int state=0;state<numStates;++state) {
			//sign of 0 does not contribute to score
			if(coeffs[state].compareTo(BigDecimal.ZERO)==0) continue;
			if(scoreType == ScoreType.FSCORE || scoreType == ScoreType.DISCREPANCY) {
				ans = ans.add(coeffs[state].multiply(getStateScoreF(state, assignment, gScores[state])));

				if(scoreType == ScoreType.DISCREPANCY)
					gScore = gScore.add(coeffs[state].multiply(gScores[state].getScore()));
			}
			else if(scoreType == ScoreType.HSCORE)
				ans = ans.add(coeffs[state].multiply(getStateScoreH(state, assignment)));
		}

		if(scoreType == ScoreType.DISCREPANCY)
			ans = ans.subtract(gScore).abs();

		return ans;
	}

	protected ArrayList<ResidueAssignmentScore> scoreUnassignedPos(LMB objFcn, 
			MSSearchProblem[][] objFcnSearch,
			KStarScore[] objFcnScores,
			ArrayList<Integer> unassignedPos) {

		int numSubStates = objFcnSearch[0].length;
		MSSearchProblem complex = objFcnSearch[0][numSubStates-1];

		ArrayList<ResidueAssignment> residueAssignments = new ArrayList<>();

		//get assignments
		for(int splitPos : unassignedPos) {

			if(complex.getNumAssignedPos()==0) {//root, split >=2 pos
				residueAssignments = getUnboundResidueAssignments(objFcnSearch[0]);
				break;
			}

			else {//can directly score bound state
				residueAssignments.add(getBoundResidueAssignments(objFcnSearch[0], splitPos));
			}
		}

		//assignments.trimToSize();

		ArrayList<ResidueAssignmentScore> assignmentScores = new ArrayList<>();
		for(ResidueAssignment residueAssignment : residueAssignments) {
			//if coeff[state]<0: want ub ratio, so smallest unbound state, largest bound state
			//if coeff[state]>0: want lb ratio, so largest unbound state, smallest bound state
			BigDecimal score = getFScore(residueAssignment, objFcn.getCoeffs(), objFcnScores);
			assignmentScores.add(new ResidueAssignmentScore(residueAssignment, score));
		}

		assignmentScores.trimToSize();
		return assignmentScores;
	}

	protected ResidueAssignment getBestResidueAssignment(ArrayList<ResidueAssignmentScore> order) {
		//sort assignments by decreasing score
		Collections.sort(order, new Comparator<ResidueAssignmentScore>() {
			@Override
			public int compare(ResidueAssignmentScore a1, ResidueAssignmentScore a2) {
				return a1.score.compareTo(a2.score)>=0 ? -1 : 1;
			}
		});

		ResidueAssignment best = order.get(0).assignment;
		return best;
	}

	/**
	 * get bound state aa assignments that don't exceed the number of allowed 
	 * mutations from wt
	 * @param search
	 * @param splitPos
	 * @param numMaxMut
	 * @return
	 */
	private ArrayList<ArrayList<AAAssignment>> getBoundAAAssignments(
			MSSearchProblem[][] objFcnSearch, 
			ArrayList<Integer> splitPos, 
			int numMaxMut
			) {
		MSSearchProblem complex = objFcnSearch[0][objFcnSearch[0].length-1];

		ArrayList<ArrayList<AAAssignment>> ans = new ArrayList<>();
		String[] wt = MSKStarNode.WT_SEQS.get(0);
		String[] buf = new String[wt.length];
		getBoundAAAssignmentsHelper(complex.settings.AATypeOptions, ans, splitPos, wt, buf, 0, 0, numMaxMut);

		ans.trimToSize();
		return ans;
	}

	protected ArrayList<ArrayList<ArrayList<AAAssignment>>> getAllowedAAAsignments(
			MSSearchProblem[][] objFcnSearch,
			ResidueAssignment residueAssignment, 
			int numMaxMut
			) {
		//we only care about bound state assignment
		ArrayList<Integer> splitPos = residueAssignment.get(residueAssignment.length()-1);
		ArrayList<ArrayList<AAAssignment>> boundAAAssignments = getBoundAAAssignments(objFcnSearch, splitPos, numMaxMut);

		ArrayList<ArrayList<ArrayList<AAAssignment>>> ans = new ArrayList<>();
		int state = 0;
		int numSubStates = objFcnSearch[state].length;
		MSSearchProblem boundState = objFcnSearch[state][numSubStates-1];
		//get corresponding unbound state splits
		for(int subState=0;subState<numSubStates-1;++subState) {
			MSSearchProblem unbound = objFcnSearch[state][subState];
			ans.add(getUnboundAAAssignments(boundState, boundAAAssignments, unbound));
		}
		ans.add(boundAAAssignments);

		ans.trimToSize();
		return ans;
	}

	public ArrayList<ResidueAssignmentScore> getAllResidueAssignments(
			LMB objFcn, 
			MSSearchProblem[][] objFcnSearch,
			KStarScore[] objFcnScores, 
			int numMaxMut
			) {
		//get number of unassigned positions
		int complex = objFcnSearch[0].length-1;
		ArrayList<Integer> unassignedPos = objFcnSearch[0][complex].getPosNums(false);

		if(unassignedPos.size()==0)
			throw new RuntimeException("ERROR: there are no unassigned positions");

		else if(unassignedPos.size()>0) {
			//"g-score": value assigned residues
			setResidueValues(objFcnSearch, true, MSKStarNode.PARALLEL_EXPANSION);

			//"h-score": value unassigned residues
			setResidueValues(objFcnSearch, false, MSKStarNode.PARALLEL_EXPANSION);
		}
		
		//score unassigned residues by objfcn
		ArrayList<ResidueAssignmentScore> scores = scoreUnassignedPos(objFcn, objFcnSearch, objFcnScores, unassignedPos);

		return scores;
	}

	@Override
	public ArrayList<ArrayList<ArrayList<AAAssignment>>> getNextAssignments(
			LMB objFcn,
			MSSearchProblem[][] objFcnSearch,
			KStarScore[] objFcnScores,
			int numMaxMut
			) {
		ArrayList<ResidueAssignmentScore> scores = getAllResidueAssignments(objFcn, objFcnSearch, objFcnScores, numMaxMut);

		//order unassigned residues by score and return best residue
		ResidueAssignment best = getBestResidueAssignment(scores);

		//convert to aas that don't violate the allowed number of mutations
		return getAllowedAAAsignments(objFcnSearch, best, numMaxMut);
	}

}
