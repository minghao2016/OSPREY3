package edu.duke.cs.osprey.partcr;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import edu.duke.cs.osprey.TestBase;
import edu.duke.cs.osprey.confspace.ConfSpace;
import edu.duke.cs.osprey.confspace.RC;
import edu.duke.cs.osprey.partcr.RCSplits.RCInfo;

public class TestRCSplits extends TestBase {
	
	@BeforeClass
	public static void before() {
		initDefaultEnvironment();
	}
	
	private ConfSpace makeConfSpace() {
		
		// need to get RCs from anywhere, doesn't matter where
		
		EnergyMatrixConfig emConfig = new EnergyMatrixConfig();
		emConfig.pdbPath = "test/DAGK/2KDC.P.forOsprey.pdb";
		emConfig.numFlexible = 2;
		emConfig.addWtRots = false;
		emConfig.doMinimize = true;
		return makeSearchProblem(emConfig).confSpace;
	}
	
	@Test
	public void testBase() {
		ConfSpace confSpace = makeConfSpace();
		RCSplits splits = new RCSplits(confSpace);
		
		RCInfo info;
	
		// just spot check a few RCs
		info = splits.getRCInfo(0, 0);
		assertThat(info.isSplit(), is(false));
		assertThat(info.isParent(0), is(true));
		assertThat(info.isParent(1), is(false));
		assertThat(splits.getRCInfo(confSpace.posFlex.get(0).RCs.get(0)), is(info));
		
		info = splits.getRCInfo(1, 5);
		assertThat(info.isSplit(), is(false));
		assertThat(info.isParent(5), is(true));
		assertThat(info.isParent(0), is(false));
		assertThat(splits.getRCInfo(confSpace.posFlex.get(1).RCs.get(5)), is(info));
	}
	
	@Test
	public void testOneSplit() {
		ConfSpace confSpace = makeConfSpace();
		RCSplits splits = new RCSplits(confSpace);
		
		// make the split
		RC parentRC = confSpace.posFlex.get(1).RCs.get(5);
		List<RC> childRCs = new BinaryRCSplitter().split(1, parentRC);
		assertThat(childRCs, is(not(nullValue())));
		splits.split(parentRC, childRCs);
		
		// check the results
		RCInfo info;
		
		// nothing should have changed at 0,0
		info = splits.getRCInfo(0, 0);
		assertThat(info.isSplit(), is(false));
		assertThat(info.isParent(0), is(true));
		assertThat(info.isParent(1), is(false));
		assertThat(splits.getRCInfo(confSpace.posFlex.get(0).RCs.get(0)), is(info));
		
		// check changes at 1,5
		info = splits.getRCInfo(1, 5);
		assertThat(info.isSplit(), is(true));
		assertThat(info.isChild(childRCs.get(0).RCIndex), is(true));
		assertThat(info.isChild(childRCs.get(1).RCIndex), is(true));
		assertThat(info.isChild(0), is(false));
		assertThat(splits.getRCInfo(confSpace.posFlex.get(1).RCs.get(5)), is(nullValue()));
		assertThat(splits.getRCInfo(childRCs.get(0)), is(info));
		assertThat(splits.getRCInfo(childRCs.get(1)), is(info));
	}
	
	@Test
	public void testTwoSplits() {
		ConfSpace confSpace = makeConfSpace();
		RCSplits splits = new RCSplits(confSpace);
		
		// make first split
		RC parentRC = confSpace.posFlex.get(1).RCs.get(5);
		List<RC> childRCs = new BinaryRCSplitter().split(1, parentRC);
		assertThat(childRCs, is(not(nullValue())));
		splits.split(parentRC, childRCs);
		
		// make second split
		List<RC> childChildRCs = new BinaryRCSplitter().split(1, childRCs.get(0));
		assertThat(childChildRCs, is(not(nullValue())));
		splits.split(childRCs.get(0), childChildRCs);
		
		// check the results
		RCInfo info;
		
		// nothing should have changed at 0,0
		info = splits.getRCInfo(0, 0);
		assertThat(info.isSplit(), is(false));
		assertThat(info.isParent(0), is(true));
		assertThat(info.isParent(1), is(false));
		assertThat(splits.getRCInfo(confSpace.posFlex.get(0).RCs.get(0)), is(info));
		
		// check changes at 1,5
		info = splits.getRCInfo(1, 5);
		assertThat(info.isSplit(), is(true));
		assertThat(info.isChild(childRCs.get(0).RCIndex), is(true));
		assertThat(info.isChild(childRCs.get(1).RCIndex), is(true));
		assertThat(info.isChild(childChildRCs.get(0).RCIndex), is(true));
		assertThat(info.isChild(childChildRCs.get(1).RCIndex), is(true));
		assertThat(info.isChild(0), is(false));
		assertThat(splits.getRCInfo(confSpace.posFlex.get(1).RCs.get(5)), is(nullValue()));
		assertThat(splits.getRCInfo(childRCs.get(0)), is(nullValue()));
		assertThat(splits.getRCInfo(childRCs.get(1)), is(info));
		assertThat(splits.getRCInfo(childChildRCs.get(0)), is(info));
		assertThat(splits.getRCInfo(childChildRCs.get(1)), is(info));
	}
}
