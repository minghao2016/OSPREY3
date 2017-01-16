package edu.duke.cs.osprey.ematrix;

import static org.junit.Assert.*;

import org.junit.BeforeClass;
import org.junit.Test;

import edu.duke.cs.osprey.TestBase;
import edu.duke.cs.osprey.confspace.SearchProblem;
import edu.duke.cs.osprey.energy.forcefield.ForcefieldParams;

public class TestSimpleEnergyMatrixCalculator extends TestBase {
	
	public static enum Type {
		
		Cpu {
			@Override
			public SimpleEnergyMatrixCalculator make(SearchProblem search, int parallelism) {
				return new SimpleEnergyMatrixCalculator.Cpu(parallelism, ffparams, search.confSpace, search.shellResidues);
			}
		},
		Gpu {
			@Override
			public SimpleEnergyMatrixCalculator make(SearchProblem search, int parallelism) {
				return new SimpleEnergyMatrixCalculator.Cuda(1, parallelism, ffparams, search.confSpace, search.shellResidues);
			}
		};
		
		public abstract SimpleEnergyMatrixCalculator make(SearchProblem search, int parallelism);
	}

	private static ForcefieldParams ffparams;
	
	@BeforeClass
	public static void before() {
		initDefaultEnvironment();
		ffparams = makeDefaultFFParams();
	}
	
	@Test
	public void testRigidCpu() {
		test(false, Type.Cpu, 1);
	}
	
	@Test
	public void testRigidCpuTwo() {
		test(false, Type.Cpu, 2);
	}
	
	@Test
	public void testRigidGpu() {
		test(false, Type.Gpu, 1);
	}
	
	@Test
	public void testRigidGpuTwo() {
		test(false, Type.Gpu, 2);
	}
	
	@Test
	public void testContinuousCpu() {
		test(true, Type.Cpu, 1);
	}
	
	@Test
	public void testContinuousCpuTwo() {
		test(true, Type.Cpu, 2);
	}
	
	@Test
	public void testContinuousGpu() {
		test(true, Type.Gpu, 1);
	}
	
	@Test
	public void testContinuousGpuTwo() {
		test(true, Type.Gpu, 2);
	}
	
	private void test(boolean doMinimize, Type type, int parallelism) {
		
		EnergyMatrixConfig emConfig = new EnergyMatrixConfig();
		emConfig.pdbPath = "examples/DAGK/2KDC.P.forOsprey.pdb";
		emConfig.numFlexible = 3;
		emConfig.addWtRots = true;
		emConfig.doMinimize = doMinimize;
		SearchProblem search = makeSearchProblem(emConfig);
		
		// calc the emat
		SimpleEnergyMatrixCalculator ematcalc = type.make(search, parallelism);
		EnergyMatrix emat = ematcalc.calcEnergyMatrix();
		ematcalc.cleanup();
		
		final double Epsilon;
		if (doMinimize) {
			Epsilon = 1e-10;
		} else {
			Epsilon = 1e-12;
		}
		
		double exp;
		double obs;
		
    	for (int pos1=0; pos1<search.confSpace.numPos; pos1++) {
			for (int rc1=0; rc1<search.emat.getNumConfAtPos(pos1); rc1++) {

				// singles
				exp = search.emat.getOneBody(pos1, rc1);
				obs = emat.getOneBody(pos1, rc1);
				assertThat(obs, isAbsolutely(exp, Epsilon));
				
				// pairs
				for (int pos2=0; pos2<pos1; pos2++) {
					for (int rc2=0; rc2<search.emat.getNumConfAtPos(pos2); rc2++) {
						
						exp = search.emat.getPairwise(pos1, rc1, pos2, rc2);
						obs = emat.getPairwise(pos1, rc1, pos2, rc2);
						assertThat(obs, isAbsolutely(exp, Epsilon));
					}
				}
			}	
    	}
	}
}
