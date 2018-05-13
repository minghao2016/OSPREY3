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
