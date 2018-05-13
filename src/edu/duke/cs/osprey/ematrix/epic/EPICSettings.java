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

package edu.duke.cs.osprey.ematrix.epic;

///////////////////////////////////////////////////////////////////////////////////////////////
//	EPICSettings.java
//
//	Version:           2.1 beta
//
//
//	  authors:
// 	  initials    name                 organization                email
//	 ---------   -----------------    ------------------------    ----------------------------
//	  MAH           Mark A. Hallen	  Duke University               mah43@duke.edu
///////////////////////////////////////////////////////////////////////////////////////////////

import cern.colt.matrix.DoubleMatrix1D;
import edu.duke.cs.osprey.control.ParamSet;
import edu.duke.cs.osprey.energy.EnergyFunction;
import edu.duke.cs.osprey.minimization.MoleculeModifierAndScorer;
import java.io.Serializable;


//This class contains the settings for EPIC: whether to use it, thresholds, etc.
//It will be referenced during all CETMatrix and A* runs.  

public class EPICSettings implements Serializable {
    
    boolean useEPIC;
    
    double EPICThresh1=10;//AKA b1
    public double EPICThresh2=25;//b2
    
    public double EPICGoalResid=1e-4;//(default = 1e-4)

    
    boolean useSAPE = true;
    boolean usePC = true;//use principal-component polynomials
    
    boolean quadOnly = false;//use only quadratic polynomials + SAPE
    
    public boolean minPartialConfs = true;//In A*, minimize sum of polynomial fits for partially
    //defined conformations, as well as for fully enumerated ones.  Increase A* lower bounds
    //but makes bound calc more time-consuming
       
    
    public boolean useEPICPruning = true;//use EPIC terms for continuous pruning
    
    public EPICSettings(){
        //by default, no EPIC
        //this is cool for operations like K* mutation list or perturbation selection
        useEPIC = false;
    }

    
    public EPICSettings(ParamSet params){
        //initialize from input parameter set
        useEPIC = params.getBool("USEEPIC");
        EPICThresh1 = params.getDouble("EPICTHRESH1");
        EPICThresh2 = params.getDouble("EPICTHRESH2");
        EPICGoalResid = params.getDouble("EPICGOALRESID");
        
        useSAPE = params.getBool("USESAPE");
        usePC = params.getBool("EPICUSEPC");
        minPartialConfs = params.getBool("MINPARTIALCONFS");

        quadOnly = params.getBool("EPICQUADONLY");
        
        useEPICPruning = params.getBool("USEEPICPRUNING");
        
        if(EPICThresh2<EPICThresh1){
            throw new RuntimeException("ERROR: EPICThresh2 must be at least EPICThresh1!  "
                    + "EPICThresh2="+EPICThresh2+" EPICThresh1="+EPICThresh1);
        }
    }
    
    public boolean shouldWeUseEPIC(){
        return useEPIC;
    }
    
    
    public static EPICSettings defaultEPIC(){
        EPICSettings ans = new EPICSettings();
        ans.useEPIC = true;
        return ans;
    }
    
    
}
