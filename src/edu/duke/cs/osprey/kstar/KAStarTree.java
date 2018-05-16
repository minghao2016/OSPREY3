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

package edu.duke.cs.osprey.kstar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.PriorityQueue;

public class KAStarTree {

	private PriorityQueue<KAStarNode> pq = null;
	
	public KAStarTree( KSAbstract ksObj, HashMap<Integer, KSAllowedSeqs> strand2AllowedSeqs, KSCalc wt ) {
		
		// initialize KUStarNode static methods
		KAStarNode.init(ksObj, strand2AllowedSeqs, wt);
		
		pq = new PriorityQueue<KAStarNode>(strand2AllowedSeqs.get(2).getNumSeqs()*2, 
				KAStarNode.KUStarNodeComparator);
	}
	
	
	public KAStarNode poll() {
		return pq.poll();
	}
	
	
	public KAStarNode peek() {
		return pq.peek();
	}
	
	
	public int size() {
		return pq.size();
	}
	
	
	public void add( KAStarNode node ) {
		pq.add(node);
	}
	
	
	public void add( ArrayList<KAStarNode> nodes ) {
		for(KAStarNode node : nodes) add(node);
	}
}




