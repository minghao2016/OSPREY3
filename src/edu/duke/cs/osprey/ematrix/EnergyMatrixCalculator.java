/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.duke.cs.osprey.ematrix;

import edu.duke.cs.osprey.confspace.ConfSpace;
import edu.duke.cs.osprey.control.EnvironmentVars;
import edu.duke.cs.osprey.ematrix.epic.EPICMatrix;
import edu.duke.cs.osprey.ematrix.epic.EPICSettings;
import edu.duke.cs.osprey.ematrix.epic.EPoly;
import edu.duke.cs.osprey.handlempi.MPIMaster;
import edu.duke.cs.osprey.handlempi.MPISlaveTask;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import edu.duke.cs.osprey.structure.Residue;
import java.util.ArrayList;
import java.util.HashMap;

/**
 *
 * @author mhall44
 */
public class EnergyMatrixCalculator {

    ConfSpace searchSpace;
    //HMN: add searchSpaceSuper
    boolean useSuperRCs = false;

    ArrayList<Residue> shellResidues;

    boolean doEPIC;
    //if we're doing EPIC, then we'll be wanting to avoid pruned RCs, and we'll need EPICSettings
    PruningMatrix pruneMat = null;
    EPICSettings epicSettings = null;

    boolean useERef = false;
    //If using E ref, will compute a reference energy for each amino-acid type
    //and correct the intra+shell energies based on the reference energies

    boolean addResEntropy = false;//add residue entropy to one-body energies

    //We are calculating either a scalar or EPIC matrix, so we'll allocate and fill in just one of these
    private EnergyMatrix emat = null;
    private EPICMatrix epicMat = null;

    boolean templateAlwaysOn = false;

    boolean verbose = true;
    //constructor for calculating a scalar energy matrix (rigid or pairwise lower bounds)

    public EnergyMatrixCalculator(ConfSpace s, ArrayList<Residue> sr, boolean useERef,
            boolean addResEntropy) {

        searchSpace = s;
        shellResidues = sr;
        doEPIC = false;
        this.useERef = useERef;
        this.addResEntropy = addResEntropy;
    }

    //Constructor for calculating an EPIC matrix
    public EnergyMatrixCalculator(ConfSpace s, ArrayList<Residue> sr, PruningMatrix pr, EPICSettings es) {
        searchSpace = s;
        shellResidues = sr;
        doEPIC = true;
        pruneMat = pr;
        epicSettings = es;
    }

    //Calculate a pairwise energy matrix based on a pairwise energy function
    public void calcPEM() {

        System.out.println();
        if (doEPIC) {
            if (verbose) {
                System.out.println("BEGINNING EPIC MATRIX PRECOMPUTATION");
            }
        } else {
            if (verbose) {
                System.out.println("BEGINNING ENERGY MATRIX PRECOMPUTATION");
            }
        }
        System.out.println();

        initMatrix();

        if (EnvironmentVars.useMPI) {
            calcPEMDistributed();
        } else {
            calcPEMLocally();
        }
        if (useERef) {
            System.out.println("COMPUTING REFERENCE ENERGIES");
            emat.eRefMat = new ReferenceEnergies(searchSpace);
            System.out.println("CORRECTING ENERGY MATRIX BASED ON REFERENCE ENERGIES");
            emat.eRefMat.correctEnergyMatrix(emat);
        }
        if (verbose) {
            System.out.println("ENERGY MATRIX CALCULATION DONE");
        }
    }

    public void calcPEMLocally() {
        //do the energy calculation here

        for (int res = 0; res < searchSpace.numPos; res++) {
            if (verbose) {
                System.out.println("Starting intra+shell energy calculations for residue " + res);
            }

            TermECalculator oneBodyECalc = new TermECalculator(searchSpace, shellResidues, doEPIC,
                    false, pruneMat, epicSettings, addResEntropy, res);

            Object oneBodyE = oneBodyECalc.doCalculation();
            storeEnergy(oneBodyE, res);

            for (int res2 = 0; res2 < res; res2++) {
                if (verbose) {
                    System.out.println("Starting pairwise energy calculations for residues " + res + ", " + res2);
                }

                TermECalculator pairECalc = new TermECalculator(searchSpace, shellResidues, doEPIC,
                        false, pruneMat, epicSettings, false, res, res2);

                Object pairE = pairECalc.doCalculation();
                storeEnergy(pairE, res, res2);
            }
        }
    }

    public void calcPEMDistributed() {
        //do energy calculation on slave nodes via MPI

        MPIMaster mm = MPIMaster.getInstance();//we'll only be running one MPI at once
        ArrayList<MPISlaveTask> tasks = new ArrayList<>();

        //generate TermMinECalc objects, in the same order as for local calculation,
        //but this time pass them off to MPI
        for (int res = 0; res < searchSpace.numPos; res++) {

            tasks.add(new TermECalculator(searchSpace, shellResidues, doEPIC, false,
                    pruneMat, epicSettings, addResEntropy, res));

            for (int res2 = 0; res2 < res; res2++) {
                tasks.add(new TermECalculator(searchSpace, shellResidues, doEPIC, false,
                        pruneMat, epicSettings, false, res, res2));
            }
        }

        ArrayList<Object> calcResults = mm.handleTasks(tasks);

        //Now go through our task results in the same order and put the energies in our matrix
        int resultCount = 0;

        for (int res = 0; res < searchSpace.numPos; res++) {

            storeEnergy(calcResults.get(resultCount), res);
            resultCount++;

            for (int res2 = 0; res2 < res; res2++) {
                storeEnergy(calcResults.get(resultCount), res, res2);
                resultCount++;
            }
        }
    }

    private void initMatrix() {
        //initialize the matrix we're calculating
        if (doEPIC) {
            if (!useSuperRCs) {
                epicMat = new EPICMatrix(searchSpace, pruneMat.getPruningInterval());
            } else {
                throw new RuntimeException("Cannot initMatrix: EPIC currently not supported with super-RCs");
            }
        } else {
            //all RCs included (infinite pruning interval)
            emat = new EnergyMatrix(searchSpace, Double.POSITIVE_INFINITY);
        }
    }

    private void storeEnergy(Object calcResult, int... pos) {
        //eCalc has performed its calculations, for the residue or pair denoted by pos.
        //store the results of this calculation in our matrix.  

        if (doEPIC) {
            if (pos.length == 1)//intra+shell energy
            {
                epicMat.oneBody.set(pos[0], (ArrayList<EPoly>) calcResult);
            } else//pairwise
            {
                epicMat.pairwise.get(pos[0]).set(pos[1], (ArrayList<ArrayList<EPoly>>) calcResult);
            }
        } else {
            if (pos.length == 0)//shell+shell energy
            {
                emat.setConstTerm((double) calcResult);
            } else if (pos.length == 1)//intra+shell energy
            {
                emat.oneBody.set(pos[0], (ArrayList<Double>) calcResult);
            } else//pairwise
            {
                emat.pairwise.get(pos[0]).set(pos[1], (ArrayList<ArrayList<Double>>) calcResult);
            }
        }

    }

    public EnergyMatrix getEMatrix() {
        if (emat == null) {
            throw new RuntimeException("ERROR: Energy matrix is null after calculation");
        }

        return emat;
    }

    public EPICMatrix getEPICMatrix() {
        if (epicMat == null) {
            throw new RuntimeException("ERROR: EPIC matrix is null after calculation");
        }

        return epicMat;
    }

}
