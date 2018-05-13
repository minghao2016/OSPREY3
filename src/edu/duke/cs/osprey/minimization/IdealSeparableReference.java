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


package edu.duke.cs.osprey.minimization;

import cern.colt.matrix.DoubleMatrix1D;
import edu.duke.cs.osprey.confspace.ConfSpace;
import edu.duke.cs.osprey.confspace.RCTuple;
import edu.duke.cs.osprey.energy.EnergyFunction;
import edu.duke.cs.osprey.minimization.MoleculeModifierAndScorer;
import edu.duke.cs.osprey.voxq.BoltzmannIntegrator1D;
import edu.duke.cs.osprey.voxq.IntraVoxelSampler;

/**
 *
 * When trying to get the free energy of a conformation,
 * it could help to have a separable reference objective function that can be integrated 1 DOF at a time
 * but whose delta G from the actual conf could be computed quickly by BAR
 * this is an idealized version of that (matches true conf energy exactly along axes)
 * to see if can get good accuracy with not too many samples
 * 
 * @author mhall44
 */
@SuppressWarnings("serial")
public class IdealSeparableReference extends MoleculeModifierAndScorer {
    //OK so the MoleculeModifierAndScorer will keep its DOF vals and actual DOFs at center
    //but we'll keep the DOF values for this IdealSeparableReference at fullCurDOFVals
    
    
    double centerE;
    double baseValForDOF[];
    DoubleMatrix1D fullCurDOFVals;
    
    public IdealSeparableReference(EnergyFunction ef, ConfSpace cSpace, RCTuple RCTup, 
            DoubleMatrix1D center){
        super(ef,cSpace,RCTup);
        super.setDOFs(center);
        centerE = efunc.getEnergy();
        fullCurDOFVals = center.copy();
        
        baseValForDOF = new double[getNumDOFs()];
        for(int dof=0; dof<getNumDOFs(); dof++){
            if(partialEFuncs != null)
                baseValForDOF[dof] = partialEFuncs.get(dof).getEnergy();
            else
                baseValForDOF[dof] = efunc.getEnergy();
        }
    }
    
    
    
    //OK so unlike a usual molecule modifier and scorer, we will just keep the molecule at the "center"
    //setDOFs, etc. will just change the curDOFVals
    
    @Override
    public void setDOFs(DoubleMatrix1D x) {
        fullCurDOFVals.assign(x);
    }
    
    
    @Override
    public void setDOF(int dof, double val) {
        fullCurDOFVals.set(dof, val);
    }
    


    @Override
    public double getValForDOF(int dof, double val) {
        //returns energy at value val along dof-axis, relative to center
        //(where axes are taken to run through center)
        double baseDOFVal = curDOFVals.get(dof);
        super.setDOF(dof, val);
        
        double ans;
        if(partialEFuncs != null)
            ans = partialEFuncs.get(dof).getEnergy();
        else
            ans = efunc.getEnergy();
        
        ans -= baseValForDOF[dof];
        
        //now go back to base value, but update fullCurDOFVals
        super.setDOF(dof, baseDOFVal);
        fullCurDOFVals.set(dof, val);
        
        return ans;
    }
    
    
        @Override
    public double getValue(DoubleMatrix1D x) {
        //separable approximation to original value based on differences along axes
        double ans = centerE;
        for(int dof=0; dof<getNumDOFs(); dof++){
            ans += getValForDOF(dof,x.get(dof));
        }
        fullCurDOFVals.assign(x);
        return ans;
    }
    
    
    @Override
    public double getCurValueOfDOF(int dof){
        return fullCurDOFVals.get(dof);
    }
    
    
    public double calcG(){
        //calculate free energy for this separable reference energy over its voxel
        double ans = centerE;
        
        for(int dof=0; dof<getNumDOFs(); dof++){
            ans -= IntraVoxelSampler.RT*Math.log( axisIntegrator(dof).doIntegral() );
        }
        
        return ans;
    }
    
    
    private BoltzmannIntegrator1D axisIntegrator(int dof){
        //integrates Boltzmann factor along one axis
        double lo = getConstraints()[0].get(dof);
        double hi = getConstraints()[1].get(dof);
        
        return new BoltzmannIntegrator1D(lo,hi){
            @Override
            public double f(double x){
                return getValForDOF(dof,x);
            }
        };
    }
}

