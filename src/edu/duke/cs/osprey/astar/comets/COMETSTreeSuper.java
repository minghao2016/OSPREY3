/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.duke.cs.osprey.astar.comets;

import edu.duke.cs.osprey.astar.AStarNode;
import edu.duke.cs.osprey.astar.AStarTree;
import edu.duke.cs.osprey.astar.ConfTreeSuper;
import edu.duke.cs.osprey.confspace.HigherTupleFinder;
import edu.duke.cs.osprey.confspace.SuperRCTuple;
import edu.duke.cs.osprey.confspace.SearchProblemSuper;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;
import edu.duke.cs.osprey.pruning.PrunerSuper;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.stream.Collectors;
import org.apache.commons.lang.ArrayUtils;

/**
 * Replica of COMETSTree but supports superRCs
 *
 * @author hmn5
 */
public class COMETSTreeSuper extends AStarTree {
    int numTreeLevels;//number of residues with sequence changes

    LME objFcn;//we are minimizing objFcn...
    LME[] constraints;//with the constraints that constr all <= 0

    ArrayList<ArrayList<String>> AATypeOptions;
    //COMETreeNode.assignments assigns each level an index in AATypeOptions.get(level), and thus an AA type
    //If -1, then no assignment yet

    int numMaxMut;//number of mutations allowed away from wtSeq (-1 means no cap)
    String wtSeq[];

    //information on states
    int numStates;//how many states there are
    //they have to have the same mutable residues & AA options,
    //though the residues involved may be otherwise different

    SearchProblemSuper[] mutableSearchProblems;//SearchProblems involved in COMETS search

    public SearchProblemSuper nonMutableSearchProblem;

    ArrayList<ArrayList<Integer>> mutable2StatePosNums;
    //mutable2StatePosNum.get(state) maps levels in this tree to flexible positions for state
    //(not necessarily an onto mapping)

    int stateNumPos[];

    int numSeqsReturned = 0;
    int stateGMECsForPruning = 0;//how many state GMECs have been calculated for nodes that are pruned    

    //HMN: To Help with Interaction E Heuristic
    int[] numMutPerStrand;
    //Maps the bound res num to the corresponding unbound emat
    List<EnergyMatrix> boundResNumToUnboundEmat;
    //Maps the bound res num to the corresponding unbound res num
    List<Integer> boundResNumToUnboundResNum;
    //Maps the bound res num to boolean that is true if res num is part of mutable
    //strand
    List<Boolean> boundResNumToIsMutableStrand;
    //determines if two residues are on the same strand
    boolean[][] belongToSameStrand;

    public COMETSTreeSuper(int numTreeLevels, LME objFcn, LME[] constraints,
            ArrayList<ArrayList<String>> AATypeOptions, int numMaxMut, String[] wtSeq,
            int numStates, SearchProblemSuper[] stateSP,
            ArrayList<ArrayList<Integer>> mutable2StatePosNums) {

        this.numTreeLevels = numTreeLevels;
        this.objFcn = objFcn;
        this.constraints = constraints;
        this.AATypeOptions = AATypeOptions;
        this.numMaxMut = numMaxMut;
        this.wtSeq = wtSeq;
        this.numStates = numStates;
        this.mutableSearchProblems = stateSP;
        this.mutable2StatePosNums = mutable2StatePosNums;

        stateNumPos = new int[numStates];
        for (int state = 0; state < numStates; state++) {
            stateNumPos[state] = stateSP[state].confSpaceSuper.numPos;
        }

    }

    public COMETSTreeSuper(int numTreeLevels, LME objFcn, LME[] constraints,
            ArrayList<ArrayList<String>> AATypeOptions, int numMaxMut, String[] wtSeq,
            int numStates, SearchProblemSuper[] stateSP,
            ArrayList<ArrayList<Integer>> mutable2StatePosNums, int[] numMutPerStrand) {

        this.numTreeLevels = numTreeLevels;
        this.objFcn = objFcn;
        this.constraints = constraints;
        this.AATypeOptions = AATypeOptions;
        this.numMaxMut = numMaxMut;
        this.wtSeq = wtSeq;
        this.numStates = numStates;
        this.mutableSearchProblems = stateSP;
        this.mutable2StatePosNums = mutable2StatePosNums;
        this.numMutPerStrand = numMutPerStrand;

        stateNumPos = new int[numStates];
        for (int state = 0; state < numStates; state++) {
            stateNumPos[state] = stateSP[state].confSpaceSuper.numPos;
        }
    }

    public COMETSTreeSuper(int numTreeLevels, LME objFcn, LME[] constraints,
            ArrayList<ArrayList<String>> AATypeOptions, int numMaxMut, String[] wtSeq,
            int numStates, SearchProblemSuper[] stateSP, SearchProblemSuper nonMutableSearchProblem,
            ArrayList<ArrayList<Integer>> mutable2StatePosNums, int[] numMutPerStrand) {

        this.numTreeLevels = numTreeLevels;
        this.objFcn = objFcn;
        this.constraints = constraints;
        this.AATypeOptions = AATypeOptions;
        this.numMaxMut = numMaxMut;
        this.wtSeq = wtSeq;
        this.numStates = numStates;
        this.mutableSearchProblems = stateSP;
        this.mutable2StatePosNums = mutable2StatePosNums;
        this.numMutPerStrand = numMutPerStrand;
        this.nonMutableSearchProblem = nonMutableSearchProblem;

        stateNumPos = new int[numStates];
        for (int state = 0; state < numStates; state++) {
            stateNumPos[state] = stateSP[state].confSpaceSuper.numPos;
        }

        this.boundResNumToUnboundEmat = getBoundPosNumToUnboundEmat();
        this.boundResNumToUnboundResNum = getBoundPosNumToUnboundPosNum();
        this.boundResNumToIsMutableStrand = getBoundPosNumberToIsMutableStrand();
        this.belongToSameStrand = getSameStrandMatrix();
    }

    @Override
    public ArrayList<AStarNode> getChildren(AStarNode curNode) {
        COMETSNodeSuper seqNode = (COMETSNodeSuper) curNode;
        ArrayList<AStarNode> ans = new ArrayList<>();

        if (seqNode.isFullyDefined()) {
            seqNode.expandConfTree();
            ans.add(seqNode);
            return ans;
        } else {
            //expand next position...            
            int curAssignments[] = seqNode.getNodeAssignments();

            for (int splitPos = 0; splitPos < numTreeLevels; splitPos++) {
                if (curAssignments[splitPos] < 0) {//can split this level

                    for (int aa = 0; aa < AATypeOptions.get(splitPos).size(); aa++) {

                        int childAssignments[] = curAssignments.clone();
                        childAssignments[splitPos] = aa;

                        UpdatedPruningMatrixSuper childPruneMat[] = new UpdatedPruningMatrixSuper[numStates];
                        for (int state = 0; state < numStates; state++) {
                            childPruneMat[state] = doChildPruning(state, seqNode.pruneMat[state], splitPos, aa);
                        }

                        COMETSNodeSuper childNode = new COMETSNodeSuper(childAssignments, childPruneMat);

                        if (splitPos == numTreeLevels - 1) {//sequence now fully defined...make conf trees
                            makeSeqConfTrees(childNode);
                        }

                        childNode.setScore(boundLME(childNode, objFcn));

                        ans.add(childNode);
                    }

                    return ans;
                }
            }

            throw new RuntimeException("ERROR: Not splittable position found but sequence not fully defined...");
        }

    }

    private UpdatedPruningMatrixSuper doChildPruning(int state, PruningMatrix parentMat, int splitPos, int aa) {
        //Create an update to parentMat (without changing parentMat)
        //to reflect that splitPos has been assigned an amino-acid type

        String assignedAAType = AATypeOptions.get(splitPos).get(aa);

        UpdatedPruningMatrixSuper ans = new UpdatedPruningMatrixSuper(parentMat);
        int posAtState = mutable2StatePosNums.get(state).get(splitPos);

        //first, prune all other AA types at splitPos
        for (int rc : parentMat.unprunedRCsAtPos(posAtState)) {
            //HUNTER: TODO: AATYperPerRes should only be one residue for now
            //We should change this to allow for superRCs at the sequence search level
            String rcAAType = mutableSearchProblems[state].confSpaceSuper.posFlexSuper.get(posAtState).superRCs.get(rc).AATypePerRes.get(0);

            if (!rcAAType.equalsIgnoreCase(assignedAAType)) {
                ans.markAsPruned(new SuperRCTuple(posAtState, rc));
            }
        }

        //now do any consequent singles & pairs pruning
        int numUpdates = ans.countUpdates();
        int oldNumUpdates;

        PrunerSuper dee = new PrunerSuper(mutableSearchProblems[state], ans, true, Double.POSITIVE_INFINITY,
                10.0, false, mutableSearchProblems[state].useTupExpForSearch, false);
        //this is rigid, type-dependent pruning aiming for sequence GMECs
        //So Ew = Ival = 0

        do {//repeat as long as we're pruning things
            oldNumUpdates = numUpdates;
            dee.prune("GOLDSTEIN");
            dee.prune("GOLDSTEIN PAIRS FULL");
            numUpdates = ans.countUpdates();
        } while (numUpdates > oldNumUpdates);

        return ans;
    }

    private void makeSeqConfTrees(COMETSNodeSuper node) {
        //Given a node with a fully defined sequence, build its conformational search trees
        //for each state
        //If a state has no viable conformations, leave it null, with stateUB[state] = inf

        node.stateTrees = new ConfTreeSuper[numStates];
        node.stateUB = new double[numStates];

        for (int state = 0; state < numStates; state++) {

            //first make sure there are RCs available at each position
            boolean RCsAvailable = true;
            for (int pos = 0; pos < stateNumPos[state]; pos++) {
                if (node.pruneMat[state].unprunedRCsAtPos(pos).isEmpty()) {
                    RCsAvailable = false;
                    break;
                }
            }

            if (RCsAvailable) {
                node.stateTrees[state] = new ConfTreeSuper(mutableSearchProblems[state], node.pruneMat[state], false);

                AStarNode rootNode = node.stateTrees[state].rootNode();

                int blankConf[] = new int[stateNumPos[state]];//set up root node UB
                Arrays.fill(blankConf, -1);
                rootNode.UBConf = blankConf;
                updateUB(state, rootNode, node);
                node.stateUB[state] = rootNode.UB;

                node.stateTrees[state].initQueue(rootNode);//allocate queue and add root node
            } else {//no confs available for this state!
                node.stateTrees[state] = null;
                node.stateUB[state] = Double.POSITIVE_INFINITY;
            }
        }
    }

    @Override
    public AStarNode rootNode() {
        int[] conf = new int[numTreeLevels];
        Arrays.fill(conf, -1);//indicates sequence not assigned

        PruningMatrix[] pruneMats = new PruningMatrix[numStates];
        for (int state = 0; state < numStates; state++) {
            pruneMats[state] = mutableSearchProblems[state].pruneMat;
        }

        COMETSNodeSuper root = new COMETSNodeSuper(conf, pruneMats);
        root.setScore(boundLME(root, objFcn));
        return root;
    }

    @Override
    public boolean canPruneNode(AStarNode node) {
        //check if the node can be pruned based on the constraints
        //each constraint function must be <=0, so if any constraint function's lower bound
        //over sequences in seqNode is > 0,
        //we can prune seqNode
        COMETSNodeSuper seqNode = (COMETSNodeSuper) node;

        if (numMaxMut != -1) {
            //we have a cap on the number of mutations...prune if exceeded
            int mutCount = 0;
            int assignments[] = seqNode.getNodeAssignments();

            for (int level = 0; level < numTreeLevels; level++) {
                if (assignments[level] >= 0) {//AA type at level is assigned
                    if (!AATypeOptions.get(level).get(assignments[level]).equalsIgnoreCase(wtSeq[level]))//and is different from wtSeq
                    {
                        mutCount++;
                    }
                }
            }

            if (mutCount > numMaxMut)//No state GMECs calcd for this...pruning based only on sequence
            {
                return true;
            }
        }

        for (LME constr : constraints) {
            if (boundLME(seqNode, constr) > 0) {
                stateGMECsForPruning += countStateGMECs(seqNode);
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isFullyAssigned(AStarNode node) {
        //This checks if the node is returnable
        //So it must be fully processed (state GMECs found, not just fully defined sequence)

        if (!node.isFullyDefined())//sequence not fully defined
        {
            return false;
        }

        COMETSNodeSuper seqNode = (COMETSNodeSuper) node;

        for (int state = 0; state < numStates; state++) {
            if (seqNode.stateTrees[state] != null) {
                AStarNode bestNodeForState = seqNode.stateTrees[state].getQueue().peek();

                if (!bestNodeForState.isFullyDefined())//State GMEC calculation not done
                {
                    return false;
                }
            }
        }

        //if we get here, all state GMECs are calculated.  Node is fully processed
        return true;
    }

    @Override
    public int[] outputNode(AStarNode node) {
        //Let's print more info when outputting a node
        printBestSeqInfo((COMETSNodeSuper) node);
        return node.getNodeAssignments();
    }

    void printBestSeqInfo(COMETSNodeSuper seqNode) {
        //About to return the given fully assigned sequence from A*
        //provide information
        System.out.println("SeqTree: A* returning conformation; lower bound = " + seqNode.getScore() + " nodes expanded: " + numExpanded);

        numSeqsReturned++;

        System.out.print("Sequence: ");

        for (int level = 0; level < numTreeLevels; level++) {
            System.out.print(AATypeOptions.get(level).get(seqNode.getNodeAssignments()[level]) + " ");
        }
        System.out.println();

        //provide state GMECs, specified as rotamers (AA types all the same of course)
        for (int state = 0; state < numStates; state++) {
            System.out.print("State " + state);

            if (seqNode.stateTrees[state] == null) {
                System.out.println(" has an unavoidable clash.");
            } else {
                System.out.print(" superRCs: ");
                int conf[] = seqNode.stateTrees[state].getQueue().peek().getNodeAssignments();
                for (int pos = 0; pos < stateNumPos[state]; pos++) {
                    System.out.print(conf[pos] + " ");
                }

                System.out.println("Energy: "
                        + getEnergyMatrix(state).confE(conf));
            }
        }
        int[] confBound = seqNode.stateTrees[0].getQueue().peek().getNodeAssignments();
        int[] confUnbound = seqNode.stateTrees[1].getQueue().peek().getNodeAssignments();
        double LMEscore = getEnergyMatrix(0).confE(confBound) - getEnergyMatrix(1).confE(confUnbound);
        System.out.println("LME: "+LMEscore);

        int numSeqDefNodes = 0;
        for (AStarNode node : getQueue()) {
            if (node.isFullyDefined()) {
                numSeqDefNodes++;
            }
        }

        System.out.println();
        System.out.println(numExpanded + " expanded; " + getQueue().size() + " nodes in tree, of which "
                + numSeqDefNodes + " are fully defined; "
                + numPruned + " pruned.");

        int stateGMECsRet = numSeqsReturned * numStates;
        int stateGMECsInTree = countGMECsInTree();
        int totGMECsCalcd = stateGMECsRet + stateGMECsInTree + stateGMECsForPruning;
        System.out.println(totGMECsCalcd + " state GMECs calculated: " + stateGMECsRet + " returned, " + stateGMECsInTree
                + " in tree, " + stateGMECsForPruning + " for pruned sequences.");

    }

    int countGMECsInTree() {
        //count how many state GMECs have been calculated and are in nodes in the tree--
        //that is, how many ConfTrees at sequence nodes have been expanded to the points
        //that their lowest-bound node is fully expanded (and thus is their GMEC)
        //for comparison, regular A* needs to calculate the GMEC for every (non-clashing)
        //(sequence,state) pair
        //Note this only counts state GMECs currently in the tree
        int count = 0;

        for (AStarNode node : getQueue()) {
            count += countStateGMECs((COMETSNodeSuper) node);
        }

        return count;
    }

    int countStateGMECs(COMETSNodeSuper seqNode) {
        //how many states for this node have GMECs calculated?
        int count = 0;

        if (seqNode.stateTrees != null) {//fully defined sequence, so there are state trees
            for (ConfTreeSuper ct : seqNode.stateTrees) {
                if (ct != null) {
                    if (ct.getQueue().peek() != null) {
                        AStarNode bestNode = ct.getQueue().peek();
                        if (bestNode.isFullyDefined()) {
                            count++;
                        }
                    }
                }
            }
        }

        return count;
    }

    private double boundLME(COMETSNodeSuper seqNode, LME func) {
        //Lower-bound func over the sequence space defined by this node
        if (seqNode.isFullyDefined())//fully-defined sequence
        {
            double originalBound = calcLBConfTrees(seqNode, func);
            //double exactBound = calcLBConfTreeMPLP(seqNode);
            return originalBound;
        } else {
            double interactionEBound = calcLBPartialSeqInteractionE(seqNode, boundResNumToUnboundEmat, boundResNumToUnboundResNum, boundResNumToIsMutableStrand, belongToSameStrand);
            double originalBound = calcLBPartialSeq(seqNode, func);
            return interactionEBound;
        }
    }

    private int[] AAOptions(int pos, int assignment) {
        //which of the AA option indices for this mutable position are allowed 
        //given this assignment in nodeAssignments?

        if (assignment == -1) {//all options allowed
            int numOptions = AATypeOptions.get(pos).size();
            int[] ans = new int[numOptions];
            for (int option = 0; option < numOptions; option++) {
                ans[option] = option;
            }

            return ans;
        } else//just the assigned option
        {
            return new int[]{assignment};
        }
    }

    private double calcLBPartialSeqInteractionE(COMETSNodeSuper seqNode, List<EnergyMatrix> boundResNumToUnboundEmat, List<Integer> boundResNumToUnboundResNum,
            List<Boolean> boundresNumToIsMutableStrand, boolean[][] belongToSameStrand) {
        ConfTreeSuper confSearchIE = new ConfTreeSuper(this.mutableSearchProblems[0], seqNode.pruneMat[0], false);
        confSearchIE.setMPLPForInteractionEnergy(boundResNumToUnboundEmat, boundResNumToUnboundResNum, boundresNumToIsMutableStrand, belongToSameStrand);
        int[] emptyConf = new int[this.mutableSearchProblems[0].confSpaceSuper.numPos];
        Arrays.fill(emptyConf, -1);
//        double lowerBoundInterE = confSearchIE.mplpMinimizer.optimizeEMPLP(emptyConf,100);
        int[] conf = confSearchIE.nextConf();
        double interactionE = confSearchIE.interactionENextConf(conf);
        return interactionE + this.mutableSearchProblems[0].emat.getConstTerm() - this.mutableSearchProblems[1].emat.getConstTerm() - this.nonMutableSearchProblem.emat.getConstTerm();
    }

    private double calcLBPartialSeq(COMETSNodeSuper seqNode, LME func) {

        int partialSeq[] = seqNode.getNodeAssignments();

        double ans = func.constTerm;
        //first terms for mutable residues
        for (int i = 0; i < numTreeLevels; i++) {
            double resE = Double.POSITIVE_INFINITY;

            for (int curAA : AAOptions(i, partialSeq[i])) {
                double AAE = 0;

                //get contributions to residue energy for this AA type from all states
                for (int state = 0; state < numStates; state++) {
                    if (func.coeffs[state] != 0) {

                        int statePosNum = mutable2StatePosNums.get(state).get(i);//residue number i converted to this state's flexible position numbering
                        boolean minForState = (func.coeffs[state] > 0);//minimizing (instead of maximizing) energy for this state

                        double stateAAE = Double.POSITIVE_INFINITY;

                        PruningMatrix pruneMat = seqNode.pruneMat[state];
                        EnergyMatrix eMatrix = getEnergyMatrix(state);

                        ArrayList<Integer> rotList = unprunedRCsAtAA(state, pruneMat,
                                i, statePosNum, curAA);

                        if (!minForState) {
                            stateAAE = Double.NEGATIVE_INFINITY;
                        }

                        for (int rot : rotList) {

                            double rotE = eMatrix.getOneBody(statePosNum, rot);

                            for (int pos2 = 0; pos2 < stateNumPos[state]; pos2++) {//all non-mut; seq only if < this one
                                if ((!mutable2StatePosNums.get(state).contains(pos2)) || pos2 < statePosNum) {

                                    double bestInteraction = Double.POSITIVE_INFINITY;
                                    ArrayList<Integer> rotList2 = pruneMat.unprunedRCsAtPos(pos2);

                                    if (!minForState) {
                                        bestInteraction = Double.NEGATIVE_INFINITY;
                                    }

                                    for (int rot2 : rotList2) {
                                        //rot2 known to be unpruned
                                        if (!pruneMat.getPairwise(statePosNum, rot, pos2, rot2)) {

                                            double pairwiseE = eMatrix.getPairwise(statePosNum, rot, pos2, rot2);
                                            pairwiseE += higherOrderContrib(state, pruneMat, statePosNum, rot, pos2, rot2, minForState);

                                            if (minForState) {
                                                bestInteraction = Math.min(bestInteraction, pairwiseE);
                                            } else {
                                                bestInteraction = Math.max(bestInteraction, pairwiseE);
                                            }
                                        }
                                    }

                                    rotE += bestInteraction;
                                }
                            }

                            if (minForState) {
                                stateAAE = Math.min(stateAAE, rotE);
                            } else {
                                stateAAE = Math.max(stateAAE, rotE);
                            }
                        }

                        if (Double.isInfinite(stateAAE)) {
                            //this will occur if the state is impossible (all confs pruned)
                            if (func.coeffs[state] > 0) {
                                AAE = Double.POSITIVE_INFINITY;
                            } else if (func.coeffs[state] < 0 && AAE != Double.POSITIVE_INFINITY) //if a "positive-design" (coeff>0) state is impossible, return +inf overall
                            {
                                AAE = Double.NEGATIVE_INFINITY;
                            }
                            //else AAE unchanged, since func doesn't involve this state
                        } else {
                            AAE += func.coeffs[state] * stateAAE;
                        }
                    }
                }

                resE = Math.min(resE, AAE);
            }

            ans += resE;
        }

        //now we bound the energy for the other residues for each of the states
        //(internal energy for that set of residues)
        for (int state = 0; state < numStates; state++) {

            if (func.coeffs[state] != 0) {
                ans += func.coeffs[state] * getEnergyMatrix(state).getConstTerm();

                double nonMutBound = boundStateNonMutE(state, seqNode, func.coeffs[state] > 0);
                //handle this like AAE above
                if (Double.isInfinite(nonMutBound)) {
                    //this will occur if the state is impossible (all confs pruned)
                    if (func.coeffs[state] > 0) {
                        return Double.POSITIVE_INFINITY;
                    } else if (func.coeffs[state] < 0) {
                        ans = Double.NEGATIVE_INFINITY;
                    }
                    //if a "positive-design" (coeff>0) state is impossible, return +inf overall still
                    //else ans unchanged, since func doesn't involve this state
                } else {
                    ans += func.coeffs[state] * nonMutBound;
                }
            }
        }

        return ans;
    }

    double higherOrderContrib(int state, PruningMatrix pruneMat,
            int pos1, int rc1, int pos2, int rc2, boolean minForState) {
        //higher-order contribution for a given RC pair in a given state, 
        //when scoring a partial conf

        EnergyMatrix emat = getEnergyMatrix(state);
        HigherTupleFinder<Double> htf = emat.getHigherOrderTerms(pos1, rc1, pos2, rc2);

        if (htf == null) {
            return 0;//no higher-order interactions
        } else {
            SuperRCTuple curPair = new SuperRCTuple(pos1, rc1, pos2, rc2);
            return higherOrderContrib(state, pruneMat, htf, curPair, minForState);
        }
    }

    private boolean posComesBefore(int pos1, int pos2, int state) {
        //Does pos1 come "before" pos2?  (Ordering: non-mut pos, then mut pos, in ascending order)
        //These are flexible positions for the given state
        boolean isMut1 = mutable2StatePosNums.get(state).contains(pos1);
        boolean isMut2 = mutable2StatePosNums.get(state).contains(pos2);

        if (isMut1 && !isMut2) {
            return false;
        }
        if (isMut2 && !isMut1) {
            return true;
        }

        //ok they are in the same category (mut or not)
        return pos1 < pos2;
    }

    double higherOrderContrib(int state, PruningMatrix pruneMat, HigherTupleFinder<Double> htf,
            SuperRCTuple startingTuple, boolean minForState) {
        //recursive function to get bound on higher-than-pairwise terms
        //this is the contribution to the bound due to higher-order interactions
        //of the RC tuple startingTuple (corresponding to htf)

        double contrib = 0;

        //to avoid double-counting, we are just counting interactions of starting tuple
        //with residues before the "earliest" one (startingLevel) in startingTuple
        //"earliest" means lowest-numbered, except non-mutating res come before mutating
        int startingLevel = startingTuple.pos.get(startingTuple.pos.size() - 1);

        for (int iPos : htf.getInteractingPos()) {//position has higher-order interaction with tup
            if (posComesBefore(iPos, startingLevel, state)) {//interaction in right order
                //(want to avoid double-counting)

                double levelBestE = Double.POSITIVE_INFINITY;//best value of contribution
                //from tup-iPos interaction

                if (!minForState) {
                    levelBestE = Double.NEGATIVE_INFINITY;
                }

                ArrayList<Integer> allowedRCs = pruneMat.unprunedRCsAtPos(iPos);

                for (int rc : allowedRCs) {

                    SuperRCTuple augTuple = startingTuple.addRC(iPos, rc);

                    if (!pruneMat.isPruned(augTuple)) {

                        double interactionE = htf.getInteraction(iPos, rc);

                        //see if need to go up to highers order again...
                        HigherTupleFinder htf2 = htf.getHigherInteractions(iPos, rc);
                        if (htf2 != null) {
                            interactionE += higherOrderContrib(state, pruneMat, htf2, augTuple, minForState);
                        }

                        //besides that only residues in definedTuple or levels below level2
                        if (minForState) {
                            levelBestE = Math.min(levelBestE, interactionE);
                        } else {
                            levelBestE = Math.max(levelBestE, interactionE);
                        }
                    }
                }

                contrib += levelBestE;//add up contributions from different interacting positions iPos
            }
        }

        return contrib;
    }

    private ArrayList<Integer> unprunedRCsAtAA(int state, PruningMatrix pruneMat,
            int mutablePos, int statePosNum, int curAA) {
        //List the RCs of the given position in the given state
        //that come with the indicated AA type (indexed in AATypeOptions)

        ArrayList<Integer> unprunedRCs = pruneMat.unprunedRCsAtPos(statePosNum);
        ArrayList<Integer> ans = new ArrayList<>();

        String curAAType = AATypeOptions.get(mutablePos).get(curAA);

        for (int rc : unprunedRCs) {
            //HMN: TODO Change to allow for more than one AA type per superRCs.
            //Currently this does not support merging
            String rcAAType = mutableSearchProblems[state].confSpaceSuper.posFlexSuper.get(statePosNum).superRCs.get(rc).AATypePerRes.get(0);

            if (rcAAType.equalsIgnoreCase(curAAType))//right type
            {
                ans.add(rc);
            }
        }

        return ans;
    }

    private double calcLBConfTreeMPLP(COMETSNodeSuper seqNode) {
        if (seqNode.stateTrees[0] == null)//state and sequence impossible
        {
            return Double.POSITIVE_INFINITY;
        }
        BigDecimal boundE = new BigDecimal(seqNode.stateTrees[0].energyNextConf());
        BigDecimal unBoundE = new BigDecimal(seqNode.stateTrees[1].energyNextConf());
        return boundE.subtract(unBoundE).add(new BigDecimal(this.objFcn.constTerm)).doubleValue();
    }

    private double calcLBConfTrees(COMETSNodeSuper seqNode, LME func) {
        //here the sequence is fully defined
        //so we can bound func solely based on lower and upper bounds (depending on func coefficient sign)
        //of the GMECs for each state, which can be derived from the front node of each state's ConfTree
        double ans = func.constTerm;
        for (int state = 0; state < numStates; state++) {
            if (func.coeffs[state] > 0) {//need lower bound

                if (seqNode.stateTrees[state] == null)//state and sequence impossible
                {
                    return Double.POSITIVE_INFINITY;
                }

                ans += func.coeffs[state] * seqNode.stateTrees[state].getQueue().peek().getScore();
            } else if (func.coeffs[state] < 0) {//need upper bound

                ConfTreeSuper curTree = seqNode.stateTrees[state];

                if (curTree == null) {//state and sequence impossible
                    //bound would be +infinity
                    ans = Double.NEGATIVE_INFINITY;
                    continue;
                }

                //make sure stateUB is updated, at least based on the current best node in this state's tree
                PriorityQueue<AStarNode> curExpansion = curTree.getQueue();

                AStarNode curNode = curExpansion.peek();

                if (Double.isNaN(curNode.UB)) {//haven't calculated UB, so calculate and update stateUB
                    while (!updateUB(state, curNode, seqNode)) {
                        //if UB not calc'd yet, curNode.UBConf is from curNode's parent
                        //if updateUB fails then the node has an inevitable clash...remove it from the tree
                        curExpansion.poll();
                        if (curExpansion.isEmpty()) {//no more nodes, so no available states!
                            //bound would be +infinity
                            seqNode.stateUB[state] = Double.POSITIVE_INFINITY;
                            ans = Double.NEGATIVE_INFINITY;
                            curNode = null;
                            break;
                        }

                        curNode = curExpansion.peek();
                        if (!Double.isNaN(curNode.UB)) {
                            break;
                        }
                    }
                }

                if (curNode == null) {//no nodes left for this state
                    ans = Double.NEGATIVE_INFINITY;
                    continue;
                }

                seqNode.stateUB[state] = Math.min(seqNode.stateUB[state], curNode.UB);

                ans += func.coeffs[state] * seqNode.stateUB[state];
            }

            //shell-shell energies may differ between states!
            //THIS DOUBLE-COUNTS BC SCORES ALREADY INCLUDE CONST TERM
            //ans += func.coeffs[state]*getEnergyMatrix(state).getConstTerm();
        }

        return ans;
    }

    private EnergyMatrix getEnergyMatrix(int state) {
        if (mutableSearchProblems[state].useTupExpForSearch)//Using LUTE
        {
            return mutableSearchProblems[state].tupExpEMat;
        } else//discrete flexibility w/o LUTE
        {
            return mutableSearchProblems[state].emat;
        }
    }

    private double boundStateNonMutE(int state, COMETSNodeSuper seqNode, boolean minForState) {
        //get a quick lower or upper bound (as indicated) for the energy of the given state's
        //non-mutable residues (their intra+shell energies + pairwise between them)
        //use pruning information from seqNode
        double ans = 0;

        PruningMatrix pruneMat = seqNode.pruneMat[state];
        EnergyMatrix eMatrix = getEnergyMatrix(state);

        for (int pos = 0; pos < stateNumPos[state]; pos++) {
            if ((!mutable2StatePosNums.get(state).contains(pos))) {

                double resE = Double.POSITIVE_INFINITY;

                ArrayList<Integer> rotList = pruneMat.unprunedRCsAtPos(pos);

                if (!minForState) {
                    resE = Double.NEGATIVE_INFINITY;
                }

                for (int rot : rotList) {
                    //make sure rot isn't pruned
                    if (!pruneMat.getOneBody(pos, rot)) {

                        double rotE = eMatrix.getOneBody(pos, rot);

                        for (int pos2 = 0; pos2 < stateNumPos[state]; pos2++) {//all non-mut; seq only if < this one
                            if ((!mutable2StatePosNums.get(state).contains(pos2)) && pos2 < pos) {

                                double bestInteraction = Double.POSITIVE_INFINITY;
                                ArrayList<Integer> rotList2 = pruneMat.unprunedRCsAtPos(pos2);
                                if (!minForState) {
                                    bestInteraction = Double.NEGATIVE_INFINITY;
                                }

                                for (int rot2 : rotList2) {
                                    if (!pruneMat.getPairwise(pos, rot, pos2, rot2)) {
                                        double pairwiseE = eMatrix.getPairwise(pos, rot, pos2, rot2);

                                        pairwiseE += higherOrderContrib(state, pruneMat, pos, rot, pos2, rot2, minForState);

                                        if (minForState) {
                                            bestInteraction = Math.min(bestInteraction, pairwiseE);
                                        } else {
                                            bestInteraction = Math.max(bestInteraction, pairwiseE);
                                        }
                                    }
                                }

                                rotE += bestInteraction;
                            }
                        }

                        if (minForState) {
                            resE = Math.min(resE, rotE);
                        } else {
                            resE = Math.max(resE, rotE);
                        }
                    }
                }

                ans += resE;
            }
        }

        return ans;
    }

    boolean updateUB(int state, AStarNode expNode, COMETSNodeSuper seqNode) {
        //Get an upper-bound on the node by a little FASTER run, generating UBConf
        //store UBConf and UB in expNode
        //expNode is in seqNode.stateTrees[state]
        //we'll start with the starting conf (likely from a parent) if provided
        //return true unless no valid conf is possible...then false

        int assignments[] = expNode.getNodeAssignments();
        ArrayList<ArrayList<Integer>> allowedRCs = new ArrayList<>();

        for (int pos = 0; pos < stateNumPos[state]; pos++) {

            ArrayList<Integer> posOptions = new ArrayList<>();

            if (assignments[pos] == -1) {
                posOptions = seqNode.pruneMat[state].unprunedRCsAtPos(pos);
                if (posOptions.isEmpty())//no options at this position
                {
                    return false;
                }
            } else//assigned
            {
                posOptions.add(assignments[pos]);
            }

            allowedRCs.add(posOptions);
        }

        int startingConf[] = expNode.UBConf;
        int[] UBConf = startingConf.clone();
        //ok first get rid of anything in startingConf not in expNode's conf space,
        //replace with the lowest-intra+shell-E conf
        for (int level = 0; level < stateNumPos[state]; level++) {

            if (!allowedRCs.get(level).contains(startingConf[level])) {
                //if( ! levelOptions.get(level).get(expNode.conf[level]).contains( startingConf[level] ) ){

                double bestISE = Double.POSITIVE_INFINITY;
                int bestRC = allowedRCs.get(level).get(0);
                for (int rc : allowedRCs.get(level)) {
                    double ise = getEnergyMatrix(state).getOneBody(level, rc);
                    if (ise < bestISE) {
                        bestISE = ise;
                        bestRC = rc;
                    }
                }

                UBConf[level] = bestRC;
            }
        }

        double curE = getEnergyMatrix(state).confE(UBConf);
        boolean done = false;

        while (!done) {

            done = true;

            for (int level = 0; level < stateNumPos[state]; level++) {

                int testConf[] = UBConf.clone();

                for (int rc : allowedRCs.get(level)) {
                    testConf[level] = rc;

                    //if(!canPrune(testConf)){//pruned conf unlikely to be good UB
                    //would only prune if using pruned pair flags in A*
                    double testE = getEnergyMatrix(state).confE(testConf);
                    if (testE < curE) {
                        curE = testE;
                        UBConf[level] = rc;
                        done = false;
                    }
                    //}
                }
            }
        }

        expNode.UBConf = UBConf;
        expNode.UB = getEnergyMatrix(state).confE(UBConf);

        return true;
    }

    //For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the corresponding
    //unbound energy matrix
    public List<EnergyMatrix> getBoundPosNumToUnboundEmat() {
        SearchProblemSuper boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<Integer> resNumsBound = boundState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound mutable state
        SearchProblemSuper unBoundMutableState = this.mutableSearchProblems[1];
        List<Integer> resNumsUnboundMutable = unBoundMutableState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblemSuper unBoundNonMutableState = this.nonMutableSearchProblem;
        List<Integer> resNumsUnboundNonMutable = unBoundNonMutableState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toCollection(ArrayList::new));
        //Map to corresponding energy matrix
        List<EnergyMatrix> unboundEmatPerPos = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum) ? unBoundMutableState.emat : unBoundNonMutableState.emat)
                .collect(Collectors.toCollection(ArrayList::new));
        return unboundEmatPerPos;
    }

    //For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the corresponding
    //position number in the unbound matrix
    public List<Integer> getBoundPosNumToUnboundPosNum() {
        SearchProblemSuper boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<Integer> resNumsBound = boundState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound mutable state
        SearchProblemSuper unBoundMutableState = this.mutableSearchProblems[1];
        List<Integer> resNumsUnboundMutable = unBoundMutableState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblemSuper unBoundNonMutableState = this.nonMutableSearchProblem;
        List<Integer> resNumsUnboundNonMutable = unBoundNonMutableState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toCollection(ArrayList::new));
        //Map to corresponding unbound position number
        List<Integer> unboundPosNumsPerPos = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum) ? ArrayUtils.indexOf(resNumsUnboundMutable.toArray(), posNum)
                                : ArrayUtils.indexOf(resNumsUnboundNonMutable.toArray(), posNum))
                .collect(Collectors.toCollection(ArrayList::new));
        return unboundPosNumsPerPos;
    }

    //For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the corresponding
    //strand number
    public boolean[][] getSameStrandMatrix() {
        SearchProblemSuper boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<Integer> resNumsBound = boundState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toList());

        //Get res number for each flexible position in the unbound mutable state
        SearchProblemSuper unBoundMutableState = this.mutableSearchProblems[1];
        List<Integer> resNumsUnboundMutable = unBoundMutableState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toList());

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblemSuper unBoundNonMutableState = this.nonMutableSearchProblem;
        List<Integer> resNumsUnboundNonMutable = unBoundNonMutableState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toList());
        //Map to corresponding unbound position number
        List<Integer> boundPosNumToStrandNum = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum) ? 0 : 1)
                .collect(Collectors.toList());

        int numResidues = boundPosNumToStrandNum.size();
        boolean[][] belongToSameStrand = new boolean[numResidues][numResidues];
        for (int i = 0; i < numResidues; i++) {
            for (int j = i + 1; j < numResidues; j++) {
                if (boundPosNumToStrandNum.get(i).equals(boundPosNumToStrandNum.get(j))) {
                    belongToSameStrand[i][j] = true;
                    belongToSameStrand[j][i] = true;
                } else {
                    belongToSameStrand[i][j] = false;
                    belongToSameStrand[j][i] = false;
                }
            }
        }
        return belongToSameStrand;
    }

    //For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the boolean
    //that is true if the res is part of boolean strand and false otherwise
    public List<Boolean> getBoundPosNumberToIsMutableStrand() {
        SearchProblemSuper boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<Integer> resNumsBound = boundState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound mutable state
        SearchProblemSuper unBoundMutableState = this.mutableSearchProblems[1];
        List<Integer> resNumsUnboundMutable = unBoundMutableState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblemSuper unBoundNonMutableState = this.nonMutableSearchProblem;
        List<Integer> resNumsUnboundNonMutable = unBoundNonMutableState.confSpaceSuper.posFlexSuper.stream()
                .map(posFlex -> posFlex.resList.get(0).resNum)
                .collect(Collectors.toCollection(ArrayList::new));
        //Map to corresponding unbound position number
        List<Boolean> unboundPosNumsPerPos = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum))
                .collect(Collectors.toCollection(ArrayList::new));
        return unboundPosNumsPerPos;
    }
}