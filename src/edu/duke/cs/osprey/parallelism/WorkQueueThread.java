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

package edu.duke.cs.osprey.parallelism;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public abstract class WorkQueueThread<T> extends WorkThread {
	
	public BlockingQueue<T> queue;
	
	public WorkQueueThread(String name, BlockingQueue<T> queue) {
		super(name);
		this.queue = queue;
	}
	
	@Override
	public void doWork()
	throws InterruptedException {
		
		// get the next piece of work in the queue
		T work = queue.poll(200, TimeUnit.MILLISECONDS);
		if (work != null) {
			doWork(work);
		}
	}
	
	protected abstract void doWork(T work) throws InterruptedException;
}




