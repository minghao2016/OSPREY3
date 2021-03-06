package edu.duke.cs.osprey.astar.conf.order;

import edu.duke.cs.osprey.astar.conf.ConfIndex;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.astar.conf.scoring.AStarScorer;
import edu.duke.cs.osprey.markstar.framework.MARKStarNode;
import edu.duke.cs.osprey.tools.MathTools;

public class UpperLowerAStarOrder implements  AStarOrder {

	private AStarScorer gscorer;
	private AStarScorer hscorer;

	@Override
	public void setScorers(AStarScorer gscorer, AStarScorer hscorer) {
		this.gscorer = gscorer;
		this.hscorer = hscorer;
	}

	@Override
	public int getNextPos(ConfIndex confIndex, RCs rcs) {

		int bestPos = -1;
		double bestScore = Double.NEGATIVE_INFINITY;

		for (int i=0; i<confIndex.numUndefined; i++) {

			int pos = confIndex.undefinedPos[i];
			double score = scorePos(confIndex, rcs, pos);

			if (score > bestScore) {
				bestScore = score;
				bestPos = pos;
			}
		}

		if (bestPos >= 0) {
			return bestPos;
		}

		// sometimes, all the positions have infinite energies
		// so just pick one arbitrarily
		return confIndex.undefinedPos[0];
	}
	double scorePos(ConfIndex confIndex, RCs rcs, int pos) {

		// check all the RCs at this pos and aggregate the energies
		double parentScore = confIndex.node.getScore();
		try {
		    MARKStarNode.Node node = (MARKStarNode.Node) confIndex.node;
		    return MathTools.log10p1(node.getSubtreeUpperBound().subtract(node.getSubtreeLowerBound()));
		}
		catch (Exception e) {
			System.err.println("This scorer only currently works with MARK*.");
			System.exit(-1);
		}
		double reciprocalSum = 0;
		for (int rc : rcs.get(pos)) {
			double childScore = gscorer.calcDifferential(confIndex, rcs, pos, rc)
					+ hscorer.calcDifferential(confIndex, rcs, pos, rc);
			reciprocalSum += 1.0/(childScore - parentScore);
		}
		return 1.0/reciprocalSum;
	}
	@Override
	public boolean isDynamic(){return true;}
}

