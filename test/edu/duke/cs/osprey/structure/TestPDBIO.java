package edu.duke.cs.osprey.structure;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

import org.junit.Test;

import edu.duke.cs.osprey.tools.FileTools;

public class TestPDBIO {
	
	@Test
	public void test1CC8() {
		
		Molecule mol = PDBIO.readFile("examples/1CC8/1CC8.ss.pdb");
		
		assertThat(mol.residues.size(), is(72));
		
		// spot check a few residues,atoms
		
		assertRes(mol.residues.get(0), "ALA A   2", 0, "2");
		assertAtom(mol.residues.get(0).atoms.get(0), "N", "N", 14.699, 27.060, 24.044);
		assertAtom(mol.residues.get(0).atoms.get(9), "3HB", "H", 12.825, 26.532, 25.978);
		assertThat(mol.getAlternates(0).isEmpty(), is(true));
		
		assertRes(mol.residues.get(71), "LEU A  73", 71, "73");
		assertAtom(mol.residues.get(71).atoms.get(0), "N", "N", 7.624, 25.000, 9.774);
		assertAtom(mol.residues.get(71).atoms.get(19), "OXT", "O", 5.315, 27.215, 11.392);
		assertThat(mol.getAlternates(71).isEmpty(), is(true));
	}
	
	@Test
	public void test4NPD() {
		
		Molecule mol = PDBIO.readFile("examples/4NPD/4NPD.pdb");
		
		assertThat(mol.residues.size(), is(195));
		
		// spot check a few residues,atoms
		
		assertRes(mol.residues.get(0), "ALA A   1", 0, "1");
		assertAtom(mol.residues.get(0).atoms.get(0), "N", "N", 0.666, 9.647, -8.772, 23.86);
		assertAtom(mol.residues.get(0).atoms.get(11), "HB3", "H", -0.025, 11.526, -7.056, 23.49);
		
		assertThat(mol.getAlternates(0).size(), is(1));
		assertRes(mol.getAlternates(0).get(0), "ALA A   1", 0, "1");
		assertAtom(mol.getAlternates(0).get(0).atoms.get(0), "N", "N", 0.419, 9.252, -8.647, 24.12);
		assertAtom(mol.getAlternates(0).get(0).atoms.get(11), "HB3", "H", 0.169, 11.490, -7.227, 23.49);
		
		assertRes(mol.residues.get(14), "GLU A  15", 14, "15");
		assertThat(mol.residues.get(14).atoms.size(), is(15));
		assertAtom(mol.residues.get(14).atoms.get(0), "N", "N", 12.735, 16.976, 11.694, 4.03);
		assertAtom(mol.residues.get(14).atoms.get(14), "HG3", "H", 14.983, 19.618, 13.536, 6.15);
		
		assertThat(mol.getAlternates(14).size(), is(2));
		assertRes(mol.getAlternates(14).get(0), "GLU A  15", 14, "15");
		assertThat(mol.getAlternates(14).get(0).atoms.size(), is(15));
		assertAtom(mol.getAlternates(14).get(0).atoms.get(0), "N", "N", 12.878, 16.984, 11.750, 3.04);
		assertAtom(mol.getAlternates(14).get(0).atoms.get(14), "HG3", "H", 16.105, 19.267, 11.142, 6.15);
		assertRes(mol.getAlternates(14).get(1), "GLU A  15", 14, "15");
		assertThat(mol.getAlternates(14).get(1).atoms.size(), is(14));
		assertAtom(mol.getAlternates(14).get(1).atoms.get(0), "N", "N", 12.889, 17.024, 11.679, 3.60);
		assertAtom(mol.getAlternates(14).get(1).atoms.get(13), "HG3", "H", 15.305, 19.571, 13.551, 6.15);
		
		assertThat(mol.getAlternates(57).size(), is(1));
		assertRes(mol.getAlternates(57).get(0), "LYS A  58", 57, "58");
		assertAtom(mol.getAlternates(57).get(0).atoms.get(0), "N", "N", 15.456, 28.995, 29.847, 8.93);
		assertAtom(mol.getAlternates(57).get(0).atoms.get(22), "HZ3", "H", 16.269, 35.709, 27.594, 18.55);

		assertRes(mol.residues.get(58), "ZN A 101", 58, "101");
		assertAtom(mol.residues.get(58).atoms.get(0), "ZN", "Zn", 17.919, 29.932, 34.195, 5.56);
		assertThat(mol.getAlternates(58).isEmpty(), is(true));
		
		assertRes(mol.residues.get(60), "SCN A 103", 60, "103");
		assertAtom(mol.residues.get(60).atoms.get(0), "S", "S", 15.650, 26.105, 35.922, 8.13);
		assertThat(mol.getAlternates(60).isEmpty(), is(true));
		
		assertRes(mol.residues.get(64), "HOH A 204", 64, "204");
		assertAtom(mol.residues.get(64).atoms.get(0), "O", "O", 12.806, 22.871, -2.789, 6.18);
		assertThat(mol.getAlternates(64).isEmpty(), is(true));
		
		assertRes(mol.residues.get(164), "HOH A 304", 164, "304");
		assertAtom(mol.residues.get(164).atoms.get(0), "O", "O", 14.926, 22.916,4.590, 7.78);
		assertThat(mol.getAlternates(164).size(), is(1));
		assertRes(mol.getAlternates(164).get(0), "HOH A 304", 164, "304");
		assertAtom(mol.getAlternates(164).get(0).atoms.get(0), "O", "O", 15.973, 24.086, 4.831, 11.48);
	}
	
	private void assertRes(Residue res, String name, int index, String resNum) {
		assertThat(res.fullName, is(name));
		assertThat(res.indexInMolecule, is(index));
		assertThat(res.getPDBResNumber(), is(resNum));
	}
	
	private void assertAtom(Atom atom, String name, String elem, double x, double y, double z) {
		assertAtom(atom, name, elem, x, y, z, 0);
	}
	
	private void assertAtom(Atom atom, String name, String elem, double x, double y, double z, double bFactor) {
		assertThat(atom.name, is(name));
		assertThat(atom.elementType, is(elem));
		double[] coords = atom.getCoords();
		assertThat(coords[0], is(x));
		assertThat(coords[1], is(y));
		assertThat(coords[2], is(z));
		assertThat(atom.BFactor, is(bFactor));
	}
}
