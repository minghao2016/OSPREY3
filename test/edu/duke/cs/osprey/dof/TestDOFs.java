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


package edu.duke.cs.osprey.dof;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import org.junit.Test;

import edu.duke.cs.osprey.TestBase;
import edu.duke.cs.osprey.confspace.Strand;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.structure.PDBIO;
import edu.duke.cs.osprey.structure.Residue;
import edu.duke.cs.osprey.tools.Protractor;

/**
 *
 * @author mhall44
 */
public class TestDOFs extends TestBase {
    
    @Test
    public void testMutation() {
        
        Strand strand = new Strand.Builder(PDBIO.readFile("examples/1CC8/1CC8.ss.pdb")).build();
        Residue res = strand.mol.residues.get(37); // Ser 39 originally
        
        res.pucker = new ProlinePucker(strand.templateLib, res);
        ResidueTypeDOF mutDOF = new ResidueTypeDOF(strand.templateLib, res);
        
        mutDOF.mutateTo("ALA");
        assertThat(res.template.name, is("ALA"));
        
        mutDOF.mutateTo("ILE");
        assertThat(res.template.name, is("ILE"));
        
        mutDOF.mutateTo("VAL");
        assertThat(res.template.name, is("VAL"));
        
        mutDOF.mutateTo("PRO");
        assertThat(res.template.name, is("PRO"));
        
        mutDOF.mutateTo("GLY");
        assertThat(res.template.name, is("GLY"));
        
        mutDOF.mutateTo("ARG");
        assertThat(res.template.name, is("ARG"));
    }
    
    @Test
    public void testDihedral(){
        
        Molecule m = new Strand.Builder(PDBIO.readFile("examples/1CC8/1CC8.ss.pdb")).build().mol;
        Residue res = m.residues.get(37);
        
        FreeDihedral chi1 = new FreeDihedral(res,0);//Ser 39
        FreeDihedral chi2 = new FreeDihedral(res,1);
        
        chi1.apply(45);
        chi2.apply(-121);
        
        //measure dihedrals.  Start by collecting coordinates
        double N[] = res.getCoordsByAtomName("N");
        double CA[] = res.getCoordsByAtomName("CA");
        double CB[] = res.getCoordsByAtomName("CB");
        double OG[] = res.getCoordsByAtomName("OG");
        double HG[] = res.getCoordsByAtomName("HG");
        
        assertThat(Protractor.measureDihedral(new double[][] {N,CA,CB,OG}), isRelatively(45));
        assertThat(Protractor.measureDihedral(new double[][] {CA,CB,OG,HG}), isRelatively(-121));
    }
}

