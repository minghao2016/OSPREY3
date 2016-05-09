package edu.duke.cs.osprey.kstar;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;

import edu.duke.cs.osprey.confspace.ConfSearch;
import edu.duke.cs.osprey.kstar.pfunc.PFAbstract;
import edu.duke.cs.osprey.kstar.pfunc.PFAbstract.EApproxReached;

/**
 * 
 * @author Adegoke Ojewole (ao68@duke.edu)
 *
 */
@SuppressWarnings("serial")
public class KSConfQ extends Thread implements Serializable {

	private PFAbstract pf;
	private ConfSearch confSearch;
	private int minCapacity;

	// lock for queue access
	public final String lock = new String("LOCK");

	private LinkedHashSet<ArrayList<Integer>> q = null;
	private int qCap = (int)Math.pow(2, 20);
	private boolean confsExhausted = false;
	private ArrayList<Integer> tail = null;

	/**
	 * 
	 * @param pf
	 * @param confSearch
	 * @param notificationThreshold = notify queue owner when queue contains
	 * this number of conformations
	 */
	public KSConfQ( PFAbstract pf, int minCapacity, BigDecimal partialQLB ) {

		this.pf = pf;
		confSearch = pf.getConfTree(false);
		
		this.minCapacity = minCapacity;
		qCap = Math.max( minCapacity, PFAbstract.qCapacity );

		q = new LinkedHashSet<>(qCap);
	}


	public void restartConfTree() {
		confSearch = pf.getConfTree(false);
	}


	public double getNextConfBound() {

		int c[] = null;

		if( (c = confSearch.nextConf()) != null ) {
			return enQueue(c);
		}

		// should never get here
		throw new RuntimeException("ERROR: all the conformations of this sequence were pruned");

		// return Double.MAX_VALUE;
	}


	public int size() {
		return q.size();
	}


	public boolean isExhausted() {
		return confsExhausted;
	}


	public boolean canSatisfy(int requested) {
		return size() >= requested;
	}

	
	public double getConfBound( int[] conf ) {
		return pf.getConfBound(confSearch, conf, false);
	}
	

	public KSConf peekTail() {
		return size() > 0 ? new KSConf(tail, pf.getConfBound(confSearch, KSConf.list2Array(tail), false)) : null;
	}


	public KSConf peekHead() {
		if(size() == 0) return null;

		ArrayList<Integer> value = q.iterator().next();
		return new KSConf(value, pf.getConfBound(confSearch, KSConf.list2Array(value), false));
	}


	protected double enQueue( int[] conf ) {

		double energyBound = pf.getConfBound(confSearch, conf, false);
		if( energyBound == Double.POSITIVE_INFINITY ) return Double.POSITIVE_INFINITY;

		BigDecimal boltzmannWeight = pf.getBoltzmannWeight(energyBound);
		if( boltzmannWeight.compareTo(BigDecimal.ZERO) == 0 ) return Double.POSITIVE_INFINITY;

		ArrayList<Integer> list = KSConf.array2List(conf);

		if(KSAbstract.doCheckPoint && size() > 0 && energyBound < peekTail().getEnergyBound() ) return energyBound;

		if( pf.getMinimizedConfsSet().contains(list) || q.contains(list) ) return energyBound;

		q.add(list);

		tail = list;

		return energyBound;
	}


	public KSConf deQueue() {
		// assuming locks are in place
		KSConf conf = size() > 0 ? peekHead() : null;

		if(conf == null) 
			throw new RuntimeException("ERROR: attempting to dequeue from an empty list");

		q.remove(conf.getConf());

		if(size() == 0) tail = null;

		return conf;
	}


	public ConfSearch getConfSearch() {
		return confSearch;
	}
	
	
	public int getQCapacity() {
		return qCap;
	}


	public void setQCapacity( int newCap ) {
		qCap = Math.max(newCap, minCapacity);
	}


	public void cleanUp( boolean nullify ) throws InterruptedException {

		synchronized( lock ) {
			lock.notify();
		}

		this.join();

		if(nullify) nullify();
	}


	private void nullify() {
		confSearch = null;
		q = null;
		tail = null;
	}


	public void run() {

		try {

			if(confsExhausted) return;

			int conf[];

			while( true ) {

				conf = confSearch.nextConf();

				synchronized( lock ) {

					if( conf == null ) {
						confsExhausted = true;
						lock.notify();
						return;
					}

					// this means the energy lower bound is pos infinity. no need to keep enumerating
					if( enQueue(conf) == Double.POSITIVE_INFINITY ) { confsExhausted = true; lock.notify(); return; }

					// notify queue consumer ONLY if queue was empty before
					// i added latest conformation. this condition means that
					// the partition function is waiting for this signal to process
					// conformations.
					// it's wasteful to call notify for every insertion
					if( size() == minCapacity ) lock.notify();

					if( size() >= qCap ) {

						lock.notify();

						if( pf.getEpsilonStatus() != EApproxReached.FALSE ) return;
						else lock.wait();
					}

					// exit thread if we have an e-approximation
					if( pf.getEpsilonStatus() != EApproxReached.FALSE ) { lock.notify(); return; }
				}
			}

		} catch (Exception e) {
			System.out.println(e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
	}

}
