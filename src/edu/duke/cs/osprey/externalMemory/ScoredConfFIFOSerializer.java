/*
** This file is part of OSPREY 3.0
** 
** OSPREY Protein Redesign Software Version 3.0
** Copyright (C) 2001-2018 Bruce Donald Lab, Duke University
** 
** OSPREY is free software: you can redistribute it and/or modify
** it under the terms of the GNU General Public License version 2
** as published by the Free Software Foundation.
** 
** OSPREY is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
** 
** You should have received a copy of the GNU General Public License
** along with OSPREY.  If not, see <http://www.gnu.org/licenses/>.
** 
** Contact Info:
**    Bruce Donald
**    Duke University
**    Department of Computer Science
**    Levine Science Research Center (LSRC)
**    Durham
**    NC 27708-0129
**    USA
**    e-mail: www.cs.duke.edu/brd/
** 
** <signature of Bruce Donald>, Mar 1, 2018
** Bruce Donald, Professor of Computer Science
*/

package edu.duke.cs.osprey.externalMemory;

import java.nio.ByteBuffer;

import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.confspace.ConfSearch.ScoredConf;
import edu.duke.cs.tpie.serialization.SerializingFIFOQueue;

public class ScoredConfFIFOSerializer extends AssignmentsSerializer implements SerializingFIFOQueue.Serializer<ScoredConf> {
	
	public ScoredConfFIFOSerializer(RCs rcs) {
		super(rcs, Double.BYTES);
	}
	
	@Override
	public void serialize(ScoredConf conf, ByteBuffer buf) {
		writeAssignments(conf.getAssignments(), buf);
		buf.putDouble(conf.getScore());
	}
	
	@Override
	public ScoredConf deserialize(ByteBuffer buf) {
		return new ScoredConf(readAssignments(buf), buf.getDouble());
	}
}




