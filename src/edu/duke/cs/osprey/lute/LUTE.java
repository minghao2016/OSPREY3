package edu.duke.cs.osprey.lute;

import edu.duke.cs.osprey.confspace.*;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;
import edu.duke.cs.osprey.energy.ConfEnergyCalculator;
import edu.duke.cs.osprey.energy.EnergyCalculator;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import edu.duke.cs.osprey.tools.Progress;
import edu.duke.cs.osprey.tools.Stopwatch;
import org.apache.commons.math3.exception.DimensionMismatchException;
import org.apache.commons.math3.linear.*;
import smile.data.SparseDataset;
import smile.math.matrix.Matrix;
import smile.regression.LASSO;

import java.util.*;
import java.util.function.Consumer;

import static edu.duke.cs.osprey.tools.Log.log;
import static edu.duke.cs.osprey.tools.Log.logf;


public class LUTE {

	public static enum Fitter {

		/**
		 * ordinary least squares via conjugate gradient
		 *
		 * ie, plain old linear regression
		 *
		 * fastest to compute, tends to overfit a bit more than LASSO though
		 */
		OLSCG(true) {

			@Override
			public double[] fit(LinearSystem system, LinearSystem.BInfo binfo) {

				// build the linear model: Ax=b
				// except conjugate gradient needs square A, so transform to A^tAx = A^tb
				RealLinearOperator AtA = new RealLinearOperator() {

					@Override
					public int getRowDimension() {
						return system.tuples.size();
					}

					@Override
					public int getColumnDimension() {
						return system.tuples.size();
					}

					@Override
					public RealVector operate(RealVector vx)
						throws DimensionMismatchException {
						double[] x = ((ArrayRealVector)vx).getDataRef();
						double[] AtAx = system.multAt(system.multA(x));
						return new ArrayRealVector(AtAx, false);
					}
				};

				RealVector Atb = new ArrayRealVector(system.multAt(binfo.b), false);

				ConjugateGradient cg = new ConjugateGradient(100000, 1e-6, false);
				return ((ArrayRealVector)cg.solve(AtA, Atb)).getDataRef();
			}
		},

		/**
		 * least absolute shrinkage and selection operator
		 *
		 * ie, L1 regularized linear regression
		 *
		 * not as fast as conjugate gradient, but tends to overfit less,
		 * and hence require fewer training samples, so could be faster
		 * overall when considering energy calculation time too
		 */
		LASSO(false /* LASSO implementation does its own normalization */) {

			@Override
			public double[] fit(LinearSystem system, LinearSystem.BInfo binfo) {

				// explicitly instantiate A (at least it's a sparse and not a dense matrix though)
				// the LASSO implementation is actually really fast!
				SparseDataset data = new SparseDataset(system.tuples.size());
				for (int c=0; c<system.confs.size(); c++) {
					final int fc = c;
					system.forEachTupleIn(c, (t) -> {
						data.set(fc, t, 1.0);
					});
				}
				Matrix A = data.toSparseMatrix();

				// tried a bunch of stuff, these seem to work well
				double lambda = 0.1;
				double tolerance = 0.1;
				int maxIterations = 200;

				// regress!
				LASSO lasso = new LASSO(A, binfo.b, lambda, tolerance, maxIterations);
				binfo.offset += lasso.intercept();
				return lasso.coefficients();
			}
		};

		public final boolean normalize;

		private Fitter(boolean normalize) {
			this.normalize = normalize;
		}

		public abstract double[] fit(LinearSystem system, LinearSystem.BInfo binfo);
	}

	public static class LinearSystem {

		private class BInfo {
			double[] b = confEnergies.clone();
			double offset = 0.0;
			double scale = 1.0;
		}


		public final TuplesIndex tuples;
		public final List<int[]> confs;
		public final double[] confEnergies;

		public double[] tupleEnergies;
		public double tupleEnergyOffset;

		public Errors errors = null;

		public LinearSystem(TuplesIndex tuples, ConfSampler.Samples samples, Map<int[],Double> confEnergies) {

			this.tuples = tuples;

			// linearize the conformations
			confs = new ArrayList<>(samples.getAllConfs());

			// linearize the conf energies
			this.confEnergies = new double[confs.size()];
			for (int c=0; c<confs.size(); c++) {
				this.confEnergies[c] = confEnergies.get(confs.get(c));
			}
		}

		private void forEachTupleIn(int c, Consumer<Integer> callback) {
			final boolean throwIfMissingSingle = false; // we're not fitting singles
			final boolean throwIfMissingPair = true; // we always fit to dense pairs, confs shouldn't be using pruned pairs
			tuples.forEachIn(confs.get(c), throwIfMissingSingle, throwIfMissingPair, callback);
		}

		public void fit(Fitter fitter) {

			// calculate b, and normalize if needed
			BInfo binfo = new BInfo();
			if (fitter.normalize) {

				// get the range of b
				double min = Double.POSITIVE_INFINITY;
				double max = Double.NEGATIVE_INFINITY;
				for (int c=0; c<confs.size(); c++) {
					double e = this.confEnergies[c];
					min = Math.min(min, e);
					max = Math.max(max, e);
				}

				binfo.offset = min;
				binfo.scale = max - min;

				for (int c=0; c<confs.size(); c++) {
					binfo.b[c] = (binfo.b[c] - binfo.offset)/binfo.scale;
				}
			}

			double[] x = fitter.fit(this, binfo);

			calcTupleEnergies(x, binfo);
		}

		private double[] multA(double[] x) {
			double[] out = new double[confs.size()];
			for (int c=0; c<confs.size(); c++) {
				final int fc = c;
				forEachTupleIn(c, (t) -> {
					out[fc] += x[t];
				});
			}
			return out;
		}

		private double[] multAt(double[] x) {
			double[] out = new double[tuples.size()];
			for (int c=0; c<confs.size(); c++) {
				double xc = x[c];
				forEachTupleIn(c, (t) -> {
					out[t] += xc;
				});
			}
			return out;
		}

		private void calcTupleEnergies(double[] x, BInfo binfo) {

			// calculate the tuple energies in un-normalized space
			double[] energies = new double[tuples.size()];
			for (int t=0; t<tuples.size(); t++) {
				energies[t] = x[t]*binfo.scale;
			}

			setTupleEnergies(energies, binfo.offset);
		}

		public void setTupleEnergies(double[] energies, double offset) {

			this.tupleEnergies = energies;
			this.tupleEnergyOffset = offset;

			// calculate the residual
			double[] residual = multA(tupleEnergies);
			for (int c=0; c<confs.size(); c++) {
				residual[c] = (residual[c] + tupleEnergyOffset - confEnergies[c]);
			}

			// analyze the errors
			this.errors = new Errors(residual);
		}
	}

	public static class Errors {

		public final double[] residual;
		public final double min;
		public final double max;
		public final double avg;
		public final double rms;

		public Errors(double[] residual) {

			this.residual = residual;

			double sum = 0.0;
			double sumsq = 0.0;
			double min = Double.POSITIVE_INFINITY;
			double max = Double.NEGATIVE_INFINITY;

			int n = residual.length;
			for (int row=0; row<n; row++) {

				double val = Math.abs(residual[row]);

				sum += val;
				sumsq += val*val;
				min = Math.min(min, val);
				max = Math.max(max, val);
			}

			this.min = min;
			this.max = max;
			this.avg = sum/n;
			this.rms = Math.sqrt(sumsq/n);
		}

		@Override
		public String toString() {
			return String.format("range [%8.4f,%8.4f]   avg %8.4f   rms %8.4f", min, max, avg, rms);
		}
	}

	public final SimpleConfSpace confSpace;

	private final Set<RCTuple> tuples = new LinkedHashSet<>();

	private LinearSystem trainingSystem = null;
	private LinearSystem testSystem = null;

	public LUTE(SimpleConfSpace confSpace) {
		this.confSpace = confSpace;
	}

	public void addUnprunedPairTuples(PruningMatrix pmat) {

		for (int pos1=0; pos1<pmat.getNumPos(); pos1++) {
			for (int rc1=0; rc1<pmat.getNumConfAtPos(pos1); rc1++) {

				// skip pruned singles
				if (pmat.isSinglePruned(pos1, rc1)) {
					continue;
				}

				for (int pos2=0; pos2<pos1; pos2++) {
					for (int rc2=0; rc2<pmat.getNumConfAtPos(pos2); rc2++) {

						// skip pruned singles
						if (pmat.isSinglePruned(pos2, rc2)) {
							continue;
						}

						// skip pruned tuples
						if (pmat.isPairPruned(pos1, rc1, pos2, rc2)) {
							continue;
						}

						// we found it! It's an unpruned pair!
						// NOTE: make the tuple in pos2, pos1 order so the positions are already sorted
						// (because pos2 < pos1 by definition)
						addTuple(new RCTuple(pos2, rc2, pos1, rc1));
					}
				}
			}
		}
	}

	public void addUnprunedTripleTuples(PruningMatrix pmat) {

		for (int pos1=0; pos1<pmat.getNumPos(); pos1++) {
			for (int rc1=0; rc1<pmat.getNumConfAtPos(pos1); rc1++) {

				// skip pruned singles
				if (pmat.isSinglePruned(pos1, rc1)) {
					continue;
				}

				for (int pos2=0; pos2<pos1; pos2++) {
					for (int rc2=0; rc2<pmat.getNumConfAtPos(pos2); rc2++) {

						// skip pruned singles
						if (pmat.isSinglePruned(pos2, rc2)) {
							continue;
						}

						// skip pruned pairs
						if (pmat.isPairPruned(pos1, rc1, pos2, rc2)) {
							continue;
						}

						for (int pos3=0; pos3<pos2; pos3++) {
							for (int rc3=0; rc3<pmat.getNumConfAtPos(pos3); rc3++) {

								// skip pruned singles
								if (pmat.isSinglePruned(pos3, rc3)) {
									continue;
								}

								// skip pruned pairs
								if (pmat.isPairPruned(pos1, rc1, pos3, rc3)) {
									continue;
								}
								if (pmat.isPairPruned(pos2, rc2, pos3, rc3)) {
									continue;
								}

								// we found it! It's an unpruned triple!
								// NOTE: make the tuple in pos3, pos2, pos1 order so the positions are already sorted
								// (because pos3 < pos2 < pos1 by definition)
								addTuple(new RCTuple(pos3, rc3, pos2, rc2, pos1, rc1));
							}
						}
					}
				}
			}
		}
	}

	public void addTuple(RCTuple tuple) {
		tuple.checkSortedPositions();
		tuples.add(tuple);
	}

	public Set<RCTuple> getTuples() {
		// don't allow callers to directly modify the tuple set
		// we need to maintain the ordering of tuple positions
		return Collections.unmodifiableSet(tuples);
	}

	public void fit(ConfEnergyCalculator confEcalc, ConfDB.ConfTable confTable, ConfSampler sampler, double maxOverfittingScore) {

		TuplesIndex tuplesIndex = new TuplesIndex(confSpace, tuples);

		ConfSampler.Samples trainingSet = new ConfSampler.Samples(tuplesIndex);
		ConfSampler.Samples testSet = new ConfSampler.Samples(tuplesIndex);
		Map<int[],Double> energies = new Conf.Map<>();
		double overfittingScore = Double.POSITIVE_INFINITY;

		Stopwatch sw = new Stopwatch().start();

		// TODO: time-based stopping criterion?
		int samplesPerTuple = 1;
		while (true) {

			// sample the training and test sets
			logf("\nsampling at least %d confs per tuple for %d tuples...", samplesPerTuple, tuples.size());
			Stopwatch samplingSw = new Stopwatch().start();
			sampler.sampleConfsForTuples(trainingSet, samplesPerTuple);
			sampler.sampleConfsForTuples(testSet, samplesPerTuple);
			log(" done in %s", samplingSw.stop().getTime(2));

			// count all the samples
			for (int[] conf : trainingSet.getAllConfs()) {
				energies.put(conf, null);
			}
			for (int[] conf : testSet.getAllConfs()) {
				energies.put(conf, null);
			}

			// calculate energies for all the samples
			Progress progress = new Progress(energies.size());
			log("calculating energies for %d samples...", progress.getTotalWork());
			for (Map.Entry<int[],Double> entry : energies.entrySet()) {
				int[] conf = entry.getKey();
				confEcalc.calcEnergyAsync(new RCTuple(conf), confTable, (energy) -> {
					entry.setValue(energy);
					progress.incrementProgress();
				});
			}
			confEcalc.tasks.waitForFinish();

			// fit the linear system to the training set
			logf("fitting %d confs to %d tuples ...", energies.size(), this.tuples.size());
			Stopwatch trainingSw = new Stopwatch().start();
			trainingSystem = new LinearSystem(tuplesIndex, trainingSet, energies);
			//trainingSystem.fit(Fitter.LASSO);
			trainingSystem.fit(Fitter.OLSCG);
			logf(" done in %s", trainingSw.stop().getTime(2));

			// analyze the test set errors
			testSystem = new LinearSystem(tuplesIndex, testSet, energies);
			testSystem.setTupleEnergies(trainingSystem.tupleEnergies, trainingSystem.tupleEnergyOffset);

			// measure overfitting by comparing ratio of rms errors
			overfittingScore = testSystem.errors.rms/trainingSystem.errors.rms;
			if (overfittingScore <= maxOverfittingScore) {
				log("");
				break;
			}

			log("    RMS errors:  train %.4f    test %.4f    overfitting score: %.4f",
				trainingSystem.errors.rms,
				testSystem.errors.rms,
				overfittingScore
			);

			samplesPerTuple++;

			// don't keep attempting to fit forever
			// (hopefully we'll never actually get to 100 samples per tuple in the real world)
			if (samplesPerTuple == 100) {
				log("100 samples per tuples reached without meeting overfitting goals."
					+ " That's a lot of samples, probaly we'll never reach the goal?"
					+ " Aborting LUTE fitting");
				break;
			}
		}

		log("\nLUTE fitting finished in %s:\n", sw.stop().getTime(2));
		log("sampled at least %d confs per tuple for %d tuples", samplesPerTuple, tuples.size());
		log("sampled %d training confs, %d test confs, %d confs total (%.2f%% overlap)",
			trainingSet.size(), testSet.size(), energies.size(),
			100.0*(trainingSet.size() + testSet.size() - energies.size())/energies.size()
		);
		log("training errors: %s", trainingSystem.errors);
		log("    test errors: %s", testSystem.errors);
		log("total energy calculations: %d    overfitting score: %.4f <= %.4f", energies.size(), overfittingScore, maxOverfittingScore);
		// TODO: histogram of test set errors?
		log("");
	}

	public void reportConfSpaceSize(ConfSearch search) {

		// TODO: modify to compute the conf space size after pruning without enumeration

		// attempt to count the conf space by enumeration
		int confSpaceSize = 0;
		boolean isExhausted = false;
		int maxSize = testSystem.confs.size()*4;
		for (; confSpaceSize<maxSize; confSpaceSize++) {
			if (search.nextConf() == null) {
				isExhausted = true;
				break;
			}
		}

		if (isExhausted) {
			log("conf space (after all pruning) has EXACTLY %d confs", confSpaceSize);
			if (confSpaceSize == testSystem.confs.size()) {
				log("LUTE test set has entirely exhausted the conf space, can't sample any more confs");
			} else {
				log("LUTE test set used %.2f%% of the pruned conf space", 100.0*testSystem.confs.size()/confSpaceSize);
			}
		} else {
			log("conf space (after all pruning) has at least %d confs, which is many more than LUTE sampled", confSpaceSize);
		}
	}

	public LinearSystem getTrainingSystem() {
		return trainingSystem;
	}

	public LinearSystem getTestSystem() {
		return testSystem;
	}

	public LUTEConfEnergyCalculator makeConfEnergyCalculator(EnergyCalculator ecalc) {
		return new LUTEConfEnergyCalculator(this, ecalc);
	}

	public EnergyMatrix makeEnergyMatrix() {

		EnergyMatrix emat = new EnergyMatrix(confSpace);

		// the constant term is the energy offset
		emat.setConstTerm(trainingSystem.tupleEnergyOffset);

		// set singles to 0
		for (SimpleConfSpace.Position pos : confSpace.positions) {
			for (SimpleConfSpace.ResidueConf rc : pos.resConfs) {
				emat.setOneBody(pos.index, rc.index, 0.0);
			}
		}

		// set pairs
		for (int i=0; i<trainingSystem.tuples.size(); i++) {
			RCTuple tuple = trainingSystem.tuples.get(i);
			if (tuple.size() == 2) {
				emat.setPairwise(
					tuple.pos.get(0),
					tuple.RCs.get(0),
					tuple.pos.get(1),
					tuple.RCs.get(1),
					trainingSystem.tupleEnergies[i]
				);
			}
		}

		return emat;
	}
}