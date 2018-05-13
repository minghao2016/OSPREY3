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


package edu.duke.cs.osprey.energy;

import java.util.ArrayList;

import edu.duke.cs.osprey.confspace.Strand;
import edu.duke.cs.osprey.control.EnvironmentVars;
import edu.duke.cs.osprey.control.ParamSet;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.structure.PDBIO;
import edu.duke.cs.osprey.structure.Residue;

/**
 *
 * For each residue in a ligand, calculate its interactions with the target
 * 
 * @author mhall44
 */
public class LigandResEnergies {
    
    ArrayList<Residue> ligandRes;
    ArrayList<Residue> targetRes;
    
    
    public LigandResEnergies(ParamSet params){
        Molecule mol = new Strand.Builder(PDBIO.readFile(params.getFile("pdbName"))).build().mol;
        String ligandTermini[] = new String[] {params.getValue("ligandStart"),params.getValue("ligandEnd")};
        String targetTermini[] = new String[] {params.getValue("targetStart"),params.getValue("targetEnd")};
        ligandRes = mol.resListFromTermini(ligandTermini, null);
        targetRes = mol.resListFromTermini(targetTermini, null);
    }
    
    
    public void printEnergies(){
        System.out.println("PRINTING LIGAND RESIDUE ENERGIES");
        System.out.println("RES ENERGY");
        
        for(Residue res : ligandRes){
            double E = calcLigandResEnergy(res);
            System.out.println(res.getPDBResNumber() + " " + E);
        }
    }
    
    
    public double calcLigandResEnergy(Residue res){
        //calculate interactions of res (assumed to be in the ligand)
        //with the target
        double E = 0;
        for(Residue tres : targetRes){
            EnergyFunction pairEFunc = EnvironmentVars.curEFcnGenerator.resPairEnergy(res, tres);
            double pairE = pairEFunc.getEnergy();
            E += pairE;
        }
        
        return E;
    }
    
}

