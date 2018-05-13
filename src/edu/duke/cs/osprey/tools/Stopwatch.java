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

package edu.duke.cs.osprey.tools;

import java.util.concurrent.TimeUnit;

public class Stopwatch {

	private boolean isRunning;
	private long startTime;
	private long timeNs;
	
	public Stopwatch() {
		isRunning = false;
		startTime = -1;
	}
	
	public Stopwatch reset() {
		timeNs = 0;
		return this;
	}
	
	public Stopwatch start() {
		reset();
		return resume();
	}
	
	public Stopwatch resume() {
		assert (!isRunning);
		startTime = System.nanoTime();
		isRunning = true;
		return this;
	}
	
	public Stopwatch stop() {
		assert (isRunning);
		timeNs += System.nanoTime() - startTime;
		isRunning = false;
		return this;
	}
	
	public boolean isRunning() {
		return isRunning;
	}
	
	public long getTimeNs() {
		if (isRunning) {
			return timeNs + System.nanoTime() - startTime;
		} else {
			return timeNs;
		}
	}
	
	public double getTimeUs() {
		return TimeFormatter.getTimeUs(getTimeNs());
	}
	
	public double getTimeMs() {
		return TimeFormatter.getTimeMs(getTimeNs());
	}
	
	public double getTimeS() {
		return TimeFormatter.getTimeS(getTimeNs());
	}
	
	public double getTimeM() {
		return TimeFormatter.getTimeM(getTimeNs());
	}
	
	public double getTimeH() {
		return TimeFormatter.getTimeH(getTimeNs());
	}
	
	public String getTime() {
		return TimeFormatter.format(getTimeNs());
	}
	
	public String getTime(int decimals) {
		return TimeFormatter.format(getTimeNs(), decimals);
	}
	
	public String getTime(TimeUnit unit) {
		return TimeFormatter.format(getTimeNs(), unit);
	}
	
	public String getTime(TimeUnit unit, int decimals) {
		return TimeFormatter.format(getTimeNs(), unit, decimals);
	}
}
