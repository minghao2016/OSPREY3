package edu.duke.cs.osprey.sofea;

import edu.duke.cs.osprey.astar.conf.ConfIndex;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.confspace.*;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.lute.LUTEConfEnergyCalculator;
import edu.duke.cs.osprey.parallelism.Parallelism;
import edu.duke.cs.osprey.parallelism.TaskExecutor;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import edu.duke.cs.osprey.tools.*;
import edu.duke.cs.osprey.tools.MathTools.BigIntegerBounds;
import edu.duke.cs.osprey.tools.MathTools.BigDecimalBounds;
import edu.duke.cs.osprey.tools.MathTools.DoubleBounds;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static edu.duke.cs.osprey.tools.Log.log;


/**
 * TODO: doc me!
 *
 * TODO: use consistent terminology for Z and all its subdivisions
 */
public class Sofea {

	public static class Builder {

		public final MultiStateConfSpace confSpace;

		private StateConfig[] stateConfigs;
		private MathContext mathContext = new MathContext(32, RoundingMode.HALF_UP);
		private File seqdbFile = new File("seq.db");
		private MathContext seqdbMathContext = new MathContext(128, RoundingMode.HALF_UP);
		private File fringedbFile = new File("fringe.db");
		private long fringedbBytes = 10*1024*1024; // 10 MiB
		private boolean showProgress = true;
		private Parallelism parallelism = Parallelism.makeCpu(1);
		private double sweepDivisor = Math.pow(Math.E, 4.0);

		// NOTE: don't need much precision for most math, but need lots of precision for seqdb math

		public Builder(MultiStateConfSpace confSpace) {

			this.confSpace = confSpace;

			this.stateConfigs = new StateConfig[confSpace.states.size()];
		}

		public Builder configState(MultiStateConfSpace.State state, StateConfig config) {
			stateConfigs[state.index] = config;
			return this;
		}

		public Builder configEachState(Function<MultiStateConfSpace.State,StateConfig> configurator) {
			for (MultiStateConfSpace.State state : confSpace.states) {
				configState(state, configurator.apply(state));
			}
			return this;
		}

		public Builder setMathContext(MathContext val) {
			mathContext = val;
			return this;
		}

		public Builder setSeqDBFile(File val) {
			seqdbFile = val;
			return this;
		}

		public Builder setSeqDBMathContext(MathContext val) {
			seqdbMathContext = val;
			return this;
		}

		public Builder setFringeDBFile(File val) {
			fringedbFile = val;
			return this;
		}

		public Builder setFringeDBBytes(long val) {
			fringedbBytes = val;
			return this;
		}

		public Builder setFringeDBMiB(int val) {
			fringedbBytes = val*1024*1024;
			return this;
		}

		public Builder setShowProgress(boolean val) {
			showProgress = val;
			return this;
		}

		public Builder setParallelism(Parallelism val) {
			parallelism = val;
			return this;
		}

		public Builder setSweepDivisor(double val) {
			sweepDivisor = val;
			return this;
		}

		public Sofea build() {

			// make sure all the states are configured
			List<String> unconfiguredStateNames = confSpace.states.stream()
				.filter(state -> stateConfigs[state.index] == null)
				.map(state -> state.name)
				.collect(Collectors.toList());
			if (!unconfiguredStateNames.isEmpty()) {
				throw new IllegalStateException("not all states have been configured. Please configure states: " + unconfiguredStateNames);
			}

			return new Sofea(
				confSpace,
				Arrays.asList(stateConfigs),
				mathContext,
				seqdbFile,
				seqdbMathContext,
				fringedbFile,
				fringedbBytes,
				showProgress,
				parallelism,
				sweepDivisor
			);
		}
	}

	public static class StateConfig {

		public final LUTEConfEnergyCalculator luteEcalc;
		public final PruningMatrix pmat;

		public StateConfig(LUTEConfEnergyCalculator luteEcalc, PruningMatrix pmat) {
			this.luteEcalc = luteEcalc;
			this.pmat = pmat;
		}
	}

	public static class SeqResult {

		public final Sequence sequence;
		public final DoubleBounds lmfeFreeEnergy;
		public final DoubleBounds[] stateFreeEnergies;

		public SeqResult(Sequence sequence, DoubleBounds lmfeFreeEnergy, DoubleBounds[] stateFreeEnergies) {
			this.sequence = sequence;
			this.lmfeFreeEnergy = lmfeFreeEnergy;
			this.stateFreeEnergies = stateFreeEnergies;
		}
	}

	/** decides if computation should continue or not */
	public static interface Criterion {

		boolean isFinished(SeqDB seqdb, FringeDB fringedb, long sweepCount);

		public static final boolean KeepIterating = false;
		public static final boolean Terminate = true;
	}


	public final MultiStateConfSpace confSpace;
	public final List<StateConfig> stateConfigs;
	public final MathContext mathContext;
	public final File seqdbFile;
	public final MathContext seqdbMathContext;
	public final File fringedbFile;
	public final long fringedbBytes;
	public final boolean showProgress;
	public final Parallelism parallelism;
	public final double sweepDivisor;

	private final List<StateInfo> stateInfos;

	private Sofea(MultiStateConfSpace confSpace, List<StateConfig> stateConfigs, MathContext mathContext, File seqdbFile, MathContext seqdbMathContext, File fringedbFile, long fringedbBytes, boolean showProgress, Parallelism parallelism, double sweepDivisor) {

		this.confSpace = confSpace;
		this.stateConfigs = stateConfigs;
		this.mathContext = mathContext;
		this.seqdbFile = seqdbFile;
		this.seqdbMathContext = seqdbMathContext;
		this.fringedbFile = fringedbFile;
		this.fringedbBytes = fringedbBytes;
		this.showProgress = showProgress;
		this.parallelism = parallelism;
		this.sweepDivisor = sweepDivisor;

		// TODO: support single tuples for conf spaces
		// TEMP: blow up if we get single tuples
		for (MultiStateConfSpace.State state : confSpace.states) {
			if (state.confSpace.positions.size() <= 1) {
				throw new Error("TODO: support small conf spaces");
			}
		}

		// init the state info
		stateInfos = confSpace.states.stream()
			.map(state -> new StateInfo(state))
			.collect(Collectors.toList());
	}

	public StateConfig getConfig(MultiStateConfSpace.State state) {
		return stateConfigs.get(state.index);
	}

	protected StateInfo getStateInfo(MultiStateConfSpace.State state) {
		return stateInfos.get(state.index);
	}

	private BigMath bigMath() {
		return new BigMath(mathContext);
	}

	public SeqDB openSeqDB() {
		return new SeqDB(confSpace, seqdbMathContext, seqdbFile);
	}

	public FringeDB openFringeDB() {
		if (fringedbFile.exists()) {
			return FringeDB.open(confSpace, fringedbFile);
		} else {
			return FringeDB.create(confSpace, fringedbFile, fringedbBytes, mathContext);
		}
	}

	/**
	 * start a new design using the conf space,
	 * or fail if previous results already exist
	 */
	public void init() {
		init(false);
	}

	public void init(boolean overwrite) {

		// don't overwrite an existing results unless explicitly asked
		if (!overwrite && (seqdbFile.exists() || fringedbFile.exists())) {
			throw new Error("Database files already exist, will not destroy existing results at:"
				+ "\n\t" + seqdbFile.getAbsolutePath()
				+ "\n\t" + fringedbFile.getAbsolutePath()
			);
		}

		// clear old results
		seqdbFile.delete();
		fringedbFile.delete();

		// OPTIMIZATION: allocate one RCTuple instance for all the triple lookups
		// and set positions in 3-2-1 order so the positions are sorted in ascending order
		RCTuple tripleTuple = new RCTuple(0, 0, 0, 0, 0, 0);

		try (SeqDB seqdb = openSeqDB()) {
			try (FringeDB fringedb = openFringeDB()) {

				// process the root node for each state
				FringeDB.Transaction fringetx = fringedb.transaction();
				SeqDB.Transaction seqtx = seqdb.transaction();
				for (MultiStateConfSpace.State state : confSpace.states) {
					StateInfo stateInfo = stateInfos.get(state.index);

					// get a multi-sequence Z bound on the root node
					MathTools.BigDecimalBounds rootBound;
					{
						ConfIndex index = stateInfo.makeConfIndex();
						BigDecimal minZ = stateInfo.optimizeZ(index, tripleTuple, MathTools.Optimizer.Minimize);
						BigDecimal maxZ = stateInfo.optimizeZ(index, tripleTuple, MathTools.Optimizer.Maximize);
						BigIntegerBounds size = stateInfo.boundLeavesPerSequence(index);

						rootBound = new MathTools.BigDecimalBounds(
							bigMath()
								.set(minZ)
								.mult(size.lower)
								.mult(stateInfo.blute.factor)
								.get(),
							bigMath()
								.set(maxZ)
								.mult(size.upper)
								.mult(stateInfo.blute.factor)
								.get()
						);
					}

					// init the fringe with the root node
					fringetx.writeRootNode(state, rootBound, stateInfo.blute.factor);
					seqtx.addZ(state, state.confSpace.makeUnassignedSequence(), rootBound);
				}
				fringetx.commit();
				fringedb.finishSweep();
				seqtx.commit();
			}
		}
	}

	private static class Node {

		final int[] conf;
		final BigDecimalBounds zbounds;
		final BigDecimal zpath;

		Node(int[] conf, BigDecimalBounds zbounds, BigDecimal zpath) {
			this.conf = conf;
			this.zbounds = zbounds;
			this.zpath = zpath;
		}
	}

	private static class ZVal {

		final int[] conf;
		final BigDecimal z;

		ZVal(int[] conf, BigDecimal z) {
			this.conf = conf;
			this.z = z;
		}
	}

	private class NodeTransaction {

		final MultiStateConfSpace.State state;
		final int[] conf;
		final BigDecimalBounds zbounds;
		final BigDecimal zpath;

		final ConfIndex index;
		final List<Node> replacementNodes = new ArrayList<>();
		final List<ZVal> zvals = new ArrayList<>();

		NodeTransaction(MultiStateConfSpace.State state, int[] conf, BigDecimalBounds zbounds, BigDecimal zpath) {

			this.state = state;
			this.conf = conf;
			this.zbounds = zbounds;
			this.zpath = zpath;

			index = new ConfIndex(state.confSpace.positions.size());
			Conf.index(conf, index);
		}

		void addReplacementNode(ConfIndex index, BigDecimalBounds zbounds, BigDecimal zpath) {
			replacementNodes.add(new Node(Conf.make(index), zbounds, zpath));
		}

		int numReplacementNodes() {
			return replacementNodes.size();
		}

		void addZVal(ConfIndex index, BigDecimal z) {
			zvals.add(new ZVal(Conf.make(index), z));
		}

		boolean hasRoomToReplace(FringeDB.Transaction fringetx, int otherNodesInFlight) {
			return fringetx.dbHasRoomFor(replacementNodes.size() + otherNodesInFlight);
		}

		boolean replace(FringeDB.Transaction fringetx, SeqDB.Transaction seqtx) {

			boolean flushed = flushTransactionsIfNeeded(fringetx, seqtx);

			StateInfo stateInfo = stateInfos.get(state.index);

			// subtract the node zbounds
			seqtx.subZ(state, stateInfo.makeSeq(conf), zbounds);

			// update fringedb and seqdb with the replacement nodes
			for (Node replacementNode : replacementNodes) {
				fringetx.writeReplacementNode(state, replacementNode.conf, replacementNode.zbounds, replacementNode.zpath);
				seqtx.addZ(state, stateInfo.makeSeq(replacementNode.conf), replacementNode.zbounds);
			}

			// add the z values
			for (ZVal zval : zvals) {
				seqtx.addZ(state, stateInfo.makeSeq(zval.conf), zval.z);
			}

			return flushed;
		}

		boolean requeue(FringeDB.Transaction fringetx, SeqDB.Transaction seqtx) {

			boolean flushed = flushTransactionsIfNeeded(fringetx, seqtx);

			// ignore all of the seqdb changes

			// move the node to the end of the fringedb queue
			fringetx.writeReplacementNode(state, conf, zbounds, zpath);

			return flushed;
		}

		boolean flushTransactionsIfNeeded(FringeDB.Transaction fringetx, SeqDB.Transaction seqtx) {

			// skip if the fringe db transaction isn't full
			if (fringetx.txHasRoomFor(replacementNodes.size())) {
				return false;
			}

			// make sure we didn't overflow the buffer entirely
			if (replacementNodes.size() > fringetx.maxWriteBufferNodes()) {
				throw new IllegalStateException(String.format("FringeDB write buffer is too small. Holds %d nodes, but need %d nodes",
					fringetx.maxWriteBufferNodes(), replacementNodes.size()
				));
			}

			// commit both transactions at the same time
			fringetx.commit();
			seqtx.commit();

			return true;
		}
	}

	private static class StateStats {
		long read = 0;
		long expanded = 0;
		long replaced = 0;
		long added = 0;
		long requeued = 0;
	}

	/**
	 * keep sweeping over the fringe set until the criterion is satisfied
	 */
	public void refine(Criterion criterion) {
		try (SeqDB seqdb = openSeqDB()) {
		try (FringeDB fringedb = openFringeDB()) {
		try (TaskExecutor tasks = parallelism.makeTaskExecutor()) {

			// get the initial zmax
			BigDecimal[] zmax = confSpace.states.stream()
				.map(state -> fringedb.getZMax(state))
				.toArray(size -> new BigDecimal[size]);

			long sweepCount = 0;
			while (true) {

				// check the termination criterion
				if (criterion != null && criterion.isFinished(seqdb, fringedb, sweepCount)) {
					log("SOFEA finished, criterion satisfied");
					break;
				}

				// stop if we ran out of fringe nodes
				if (fringedb.isEmpty()) {
					log("SOFEA finished, explored every node");
					break;
				}

				// reduce zmax before each sweep
				for (MultiStateConfSpace.State state : confSpace.states) {
					zmax[state.index] = bigMath()
						.set(zmax[state.index])
						.div(sweepDivisor)
						.get();
				}

				// show progress if needed
				sweepCount++;
				if (showProgress) {
					log("sweep %d", sweepCount);
				}

				// init sweep stats
				StateStats[] stats = new StateStats[confSpace.states.size()];
				for (MultiStateConfSpace.State state : confSpace.states) {
					stats[state.index] = new StateStats();
				}

				// keep track of how many nodes are in outstanding tasks, and hence unknown to FringeDB's size counters
				// NOTE: use a size-one array instead of a plain var, since Java's compiler is kinda dumb about lambdas
				int[] nodesInFlight = { 0 };

				// start (or resume) the sweep
				FringeDB.Transaction fringetx = fringedb.transaction();
				SeqDB.Transaction seqtx = seqdb.transaction();
				Progress progress = new Progress(fringetx.numNodesToRead());
				while (true) {

					// read the next node
					final NodeTransaction nodetx;
					synchronized (Sofea.this) { // don't race the listener thread
						if (!fringetx.hasNodesToRead()) {
							break;
						}
						fringetx.readNode();
						nodesInFlight[0]++;
						nodetx = new NodeTransaction(
							fringetx.state(),
							fringetx.conf(),
							fringetx.zbounds(),
							fringetx.zpath()
						);
					}
					stats[nodetx.state.index].read++;

					// process nodes with tasks (possibly in parallel)
					tasks.submit(
						() -> {

							// OPTIMIZATION: allocate one RCTuple instance for all the triple lookups
							// and set positions in 3-2-1 order so the positions are sorted in ascending order
							RCTuple tripleTuple = new RCTuple(0, 0, 0, 0, 0, 0);

							return design(nodetx, zmax[nodetx.state.index], tripleTuple, nodetx.index, nodetx.zbounds, nodetx.zpath);
						},
						(wasExpanded) -> {

							if (wasExpanded) {
								stats[nodetx.state.index].expanded++;
							}

							synchronized (Sofea.this) { // don't race the main thread
								nodesInFlight[0]--;
								if (nodetx.hasRoomToReplace(fringetx, nodesInFlight[0])) {
									stats[nodetx.state.index].added += nodetx.numReplacementNodes();
									stats[nodetx.state.index].replaced++;
									nodetx.replace(fringetx, seqtx);
								} else {
									stats[nodetx.state.index].requeued++;
									nodetx.requeue(fringetx, seqtx);
								}
							}

							if (showProgress) {
								progress.incrementProgress();
							}
						}
					);
				}
				tasks.waitForFinish();
				fringetx.commit();
				seqtx.commit();
				fringedb.finishSweep();

				// show stats if needed
				if (showProgress) {
					log("\tfringe size: %d/%d (%.1f%%) nodes",
						fringedb.getNumNodes(), fringedb.getCapacity(),
						100.0f*fringedb.getNumNodes()/fringedb.getCapacity()
					);
					for (MultiStateConfSpace.State state : confSpace.states) {
						log("\t%10s  read=%6d  expanded=%6d  replaced=%6d  added=%6d requeued=%6d",
							state.name,
							stats[state.index].read,
							stats[state.index].expanded,
							stats[state.index].replaced,
							stats[state.index].added,
							stats[state.index].requeued
						);
					}
				}
			}
		}}}
	}

	private boolean design(NodeTransaction nodetx, BigDecimal zmax, RCTuple tripleTuple, ConfIndex index, BigDecimalBounds zbounds, BigDecimal zpath) {

		// skip this tree if it's too small, and add the node to the fringe set
		if (MathTools.isLessThan(zbounds.upper, zmax)) {
			nodetx.addReplacementNode(index, zbounds, zpath);
			return false;
		}

		// otherwise, recurse

		StateInfo stateInfo = stateInfos.get(nodetx.state.index);
		boolean isRoot = index.numDefined == 0;
		boolean isRCLeaf = index.numDefined + 1 == index.numPos;

		int pos = index.numDefined;
		for (int rc : stateInfo.rcs.get(pos)) {

			// update the zpath with this RC
			BigDecimal zpathrc = zpath;
			if (!isRoot) {

				BigDecimal zrc = stateInfo.getZPart(index, tripleTuple, pos, rc);

				// this subtree contributes nothing to Z
				if (MathTools.isZero(zrc)) {
					continue;
				}

				zpathrc = bigMath()
					.set(zpathrc)
					.mult(zrc)
					.get();
			}

			if (isRCLeaf) {

				assert (!isRoot);

				// hit bottom, add the leaf node to the seqdb
				index.assignInPlace(pos, rc);
				nodetx.addZVal(index, zpathrc);
				index.unassignInPlace(pos);

			} else {

				index.assignInPlace(pos, rc);

				// get the subtree Z bounds and recurse
				// but only if the upper bound is nonzero (ie we get non-null subtree bounds)
				BigDecimalBounds zboundsrc = stateInfo.boundZ(index, tripleTuple, zpathrc);
				if (zboundsrc != null) {

					// recurse
					design(nodetx, zmax, tripleTuple, index, zboundsrc, zpathrc);
				}

				index.unassignInPlace(pos);
			}
		}

		return true;
	}

	public double calcG(MultiStateConfSpace.State state, Sequence seq) {
		return new BoltzmannCalculator(mathContext).freeEnergyPrecise(calcZ(state, seq));
	}

	public BigDecimal calcZ(MultiStateConfSpace.State state, Sequence seq) {
		return stateInfos.get(state.index).calcZ(seq);
	}

	protected class StateInfo {

		final MultiStateConfSpace.State state;
		final BoltzmannLute blute;
		final PruningMatrix pmat;
		final RCs rcs;
		final List<SimpleConfSpace.Position> positions;

		private final List<TupleMatrixGeneric<BigDecimal[]>> optrc3Energies;
		private final int[][] rtsByRcByPos;
		private final int[] numRtsByPos;

		StateInfo(MultiStateConfSpace.State state) {

			this.state = state;
			StateConfig config = stateConfigs.get(state.index);
			this.blute = new BoltzmannLute(config.luteEcalc, mathContext);
			this.pmat = config.pmat;
			this.rcs = new RCs(state.confSpace);
			this.positions = state.confSpace.positions;

			// OPTIMIZATION: allocate one RCTuple instance for all the triple lookups
			// and set positions in 3-2-1 order so the positions are sorted in ascending order
			RCTuple tripleTuple = new RCTuple(0, 0, 0, 0, 0, 0);

			// pre-calculate all the optimal triple Z values for each RC pair
			optrc3Energies = new ArrayList<>(MathTools.Optimizer.values().length);
			for (MathTools.Optimizer opt : MathTools.Optimizer.values()) {
				optrc3Energies.add(new TupleMatrixGeneric<>(state.confSpace));
			}
			for (int pos1=0; pos1<rcs.getNumPos(); pos1++) {
				for (int rc1 : rcs.get(pos1)) {
					for (int pos2=0; pos2<pos1; pos2++) {
						for (int rc2 : rcs.get(pos2)) {

							for (MathTools.Optimizer opt : MathTools.Optimizer.values()) {

								BigDecimal[] pos3Energies = new BigDecimal[state.confSpace.positions.size()];
								optrc3Energies.get(opt.ordinal()).setPairwise(pos1, rc1, pos2, rc2, pos3Energies);

								for (int pos3=0; pos3<pos2; pos3++) {

									BigDecimal optrc3Energy = opt.initBigDecimal();
									for (int rc3 : rcs.get(pos3)) {
										tripleTuple.set(pos3, rc3, pos2, rc2, pos1, rc1);
										BigDecimal triple = blute.get(tripleTuple);
										if (triple != null) {
											optrc3Energy = opt.opt(optrc3Energy, triple);
										}
									}

									pos3Energies[pos3] = optrc3Energy;
								}
							}
						}
					}
				}
			}

			// calculate all the RTs by RC and pos
			rtsByRcByPos = new int[positions.size()][];
			numRtsByPos = new int[positions.size()];
			for (SimpleConfSpace.Position confPos : positions) {
				rtsByRcByPos[confPos.index] = new int[confPos.resConfs.size()];
				SeqSpace.Position seqPos = confSpace.seqSpace.getPosition(confPos.resNum);
				if (seqPos != null) {
					numRtsByPos[confPos.index] = seqPos.resTypes.size();
					for (SimpleConfSpace.ResidueConf rc : confPos.resConfs) {
						SeqSpace.ResType rt = seqPos.getResTypeOrThrow(rc.template.name);
						rtsByRcByPos[confPos.index][rc.index] = rt.index;
					}
				} else {
					numRtsByPos[confPos.index] = 0;
					Arrays.fill(rtsByRcByPos[confPos.index], Sequence.Unassigned);
				}
			}
		}

		ConfIndex makeConfIndex() {
			ConfIndex index = new ConfIndex(positions.size());
			index.updateUndefined();
			return index;
		}

		Sequence makeSeq(int[] conf) {
			Sequence seq = confSpace.seqSpace.makeUnassignedSequence();
			for (SimpleConfSpace.Position confPos : state.confSpace.positions) {
				SeqSpace.Position seqPos = confSpace.seqSpace.getPosition(confPos.resNum);
				int rc = conf[confPos.index];
				if (seqPos != null && rc != Conf.Unassigned) {
					SimpleConfSpace.ResidueConf resConf = confPos.resConfs.get(rc);
					seq.set(seqPos, resConf.template.name);
				}
			}
			return seq;
		}

		BigDecimal getZPart(ConfIndex confIndex, RCTuple tripleTuple, int pos1, int rc1) {

			assert (confIndex.numDefined > 0);

			if (pmat.getOneBody(pos1, rc1)) {
				return BigDecimal.ZERO;
			}

			tripleTuple.pos.set(2, pos1);
			tripleTuple.RCs.set(2, rc1);

			BigMath math = bigMath().set(1.0);
			for (int i=0; i<confIndex.numDefined; i++) {
				int pos2 = confIndex.definedPos[i];
				int rc2 = confIndex.definedRCs[i];

				if (pmat.getPairwise(pos1, rc1, pos2, rc2)) {
					return BigDecimal.ZERO;
				}

				tripleTuple.pos.set(1, pos2);
				tripleTuple.RCs.set(1, rc2);

				math.mult(blute.get(pos1, rc1, pos2, rc2));

				for (int j=0; j<i; j++) {
					int pos3 = confIndex.definedPos[j];
					int rc3 = confIndex.definedRCs[j];

					if (pmat.getPairwise(pos1, rc1, pos3, rc3)) {
						return BigDecimal.ZERO;
					}
					if (pmat.getPairwise(pos2, rc2, pos3, rc3)) {
						return BigDecimal.ZERO;
					}

					tripleTuple.pos.set(0, pos3);
					tripleTuple.RCs.set(0, rc3);

					if (pmat.getTuple(tripleTuple)) {
						return BigDecimal.ZERO;
					}

					BigDecimal triple = blute.get(tripleTuple);
					if (triple != null) {
						math.mult(triple);
					}
				}
			}

			return math.get();
		}

		boolean isSingleSequence(ConfIndex index) {

			for (int i=0; i<index.numUndefined; i++) {
				int pos = index.undefinedPos[i];

				// is there more than one RT at this pos?
				int rtRef = rtsByRcByPos[pos][0];
				for (int rc : rcs.get(pos)) {
					int rt = rtsByRcByPos[pos][rc];
					if (rt != rtRef) {

						// yup, multi-sequence
						return false;
					}
				}
			}

			// single-sequence
			return true;
		}

		BigDecimalBounds boundZ(ConfIndex index, RCTuple tripleTuple, BigDecimal zpath) {

			// get bounds on the size of this subtree (varying by sequence)
			BigIntegerBounds count = boundLeavesPerSequence(index);

			// short circuit if we know Z must be zero
			if (count.upper.compareTo(BigInteger.ZERO) == 0) {
				return null;
			}

			// get the upper bound
			BigDecimal zmax = optimizeZ(index, tripleTuple, MathTools.Optimizer.Maximize);

			// short circuit if needed
			if (MathTools.isZero(zmax)) {
				return null;
			}

			zmax = bigMath()
				.set(zmax)
				.mult(count.upper)
				.mult(zpath)
				.get();

			// get the lower bound
			BigDecimal zmin;
			if (isSingleSequence(index)) {
				zmin = bigMath()
					.set(minimizeZ(index, tripleTuple))
					.mult(zpath)
					.get();
			} else {
				zmin = bigMath()
					.set(optimizeZ(index, tripleTuple, MathTools.Optimizer.Minimize))
					.mult(count.lower)
					.mult(zpath)
					.get();
			}

			return new BigDecimalBounds(zmin, zmax);
		}

		/** WARNING: naive brute force method, for testing small trees only */
		BigDecimalBounds exactBoundZ(ConfIndex index, RCTuple tripleTuple) {

			BigDecimalBounds[] opt = { null };

			// NOTE: java has a hard time with recursive lambdas,
			// so use an array to work around the compiler's limitations
			@SuppressWarnings("unchecked")
			Consumer<BigDecimal>[] f = (Consumer<BigDecimal>[])new Consumer[]{null};
			f[0] = (zpath) -> {

				// if this is a leaf node, update the Z optimization
				if (index.isFullyDefined()) {
					if (opt[0] == null) {
						opt[0] = new BigDecimalBounds(zpath, zpath);
					} else if (MathTools.isLessThan(zpath, opt[0].lower)) {
						opt[0].lower = zpath;
					} else if (MathTools.isGreaterThan(zpath, opt[0].upper)) {
						opt[0].upper = zpath;
					}
					return;
				}

				// otherwise, recurse
				int pos = index.numDefined;
				for (int rc : rcs.get(pos)) {

					BigDecimal zpathrc;
					if (index.numDefined == 0) {
						zpathrc = zpath;
					} else {
						zpathrc = bigMath()
							.set(zpath)
							.mult(getZPart(index, tripleTuple, pos, rc))
							.get();
					}

					index.assignInPlace(pos, rc);
					f[0].accept(zpathrc);
					index.unassignInPlace(pos);
				}
			};
			f[0].accept(BigDecimal.ONE);

			return opt[0];
		}

		BigDecimal minimizeZ(ConfIndex index, RCTuple tripleTuple) {

			// do DFS to get *any* leaf node
			// but heuristically prefer leaf nodes with higher Z values
			// so we get a higher min bound on the Z sum
			// that requires sorting though, so for now we're just preferring non-zero Z values

			// only returns a sound lower bound on the Z sum when the subtree describes only a single sequence

			// assign the next position
			int pos = index.undefinedPos[0];

			for (int rc : rcs.get(pos)) {

				// get the zpart
				BigDecimal zpart;
				if (index.numDefined == 0) {
					zpart = BigDecimal.ONE;
				} else {
					zpart = getZPart(index, tripleTuple, pos, rc);
				}

				// heuristically prefer non-zero z values
				if (MathTools.isZero(zpart)) {
					continue;
				}

				if (index.numDefined < index.numPos - 1) {

					// have positions left to assign, so recurse
					index.assignInPlace(pos, rc);
					BigDecimal zsub = minimizeZ(index, tripleTuple);
					index.unassignInPlace(pos);

					// heuristically prefer non-zero z values
					if (MathTools.isZero(zsub)) {
						continue;
					}

					// recursion reached a leaf node, return the z!
					return bigMath()
						.set(zpart)
						.mult(zsub)
						.get();

				} else {

					// we're already at a leaf node, just return the zpart
					return zpart;
				}
			}

			return BigDecimal.ZERO;
		}

		BigDecimal optimizeZ(ConfIndex index, RCTuple tripleTuple, MathTools.Optimizer opt) {

			BigMath energy = bigMath().set(1.0);

			// get the score for each undefined position
			for (int i=0; i<index.numUndefined; i++) {
				int pos1 = index.undefinedPos[i];

				// optimize over possible assignments to pos1
				BigDecimal pos1Energy = opt.initBigDecimal();
				for (int rc1 : rcs.get(pos1)) {

					BigMath rc1Energy = bigMath();
					if (isPruned(index, tripleTuple, pos1, rc1)) {

						// prune tuple, no contribution to Z
						rc1Energy.set(0.0);

					} else {

						rc1Energy.set(1.0);

						tripleTuple.pos.set(2, pos1);
						tripleTuple.RCs.set(2, rc1);

						// interactions with defined residues
						for (int j=0; j<index.numDefined; j++) {
							int pos2 = index.definedPos[j];
							int rc2 = index.definedRCs[j];

							tripleTuple.pos.set(1, pos2);
							tripleTuple.RCs.set(1, rc2);

							rc1Energy.mult(blute.get(pos1, rc1, pos2, rc2));

							for (int k=0; k<j; k++) {
								int pos3 = index.definedPos[k];
								int rc3 = index.definedRCs[k];

								// triples are optional
								tripleTuple.pos.set(0, pos3);
								tripleTuple.RCs.set(0, rc3);
								BigDecimal triple = blute.get(tripleTuple);
								if (triple != null) {
									rc1Energy.mult(triple);
								}
							}
						}

						// interactions with undefined residues
						for (int j=0; j<i; j++) {
							int pos2 = index.undefinedPos[j];

							tripleTuple.pos.set(1, pos2);

							// optimize over possible assignments to pos2
							BigDecimal optrc2Energy = opt.initBigDecimal();
							for (int rc2 : rcs.get(pos2)) {

								tripleTuple.RCs.set(1, rc2);

								BigMath rc2Energy = bigMath();

								if (pmat.getPairwise(pos1, rc1, pos2, rc2)) {

									// prune tuple, no contribution to Z
									rc2Energy.set(0.0);

								} else {

									// pair with pos2
									rc2Energy.set(blute.get(pos1, rc1, pos2, rc2));

									// triples with defined positions
									for (int k=0; k<index.numDefined; k++) {
										int pos3 = index.definedPos[k];
										int rc3 = index.definedRCs[k];

										// triples are optional
										tripleTuple.pos.set(0, pos3);
										tripleTuple.RCs.set(0, rc3);
										BigDecimal triple = blute.get(tripleTuple);
										if (triple != null) {
											rc2Energy.mult(triple);
										}
									}

									// triples with undefined positions
									for (int k=0; k<j; k++) {
										int pos3 = index.undefinedPos[k];
										BigDecimal optrc3Energy = optrc3Energies.get(opt.ordinal()).getPairwise(pos1, rc1, pos2, rc2)[pos3];
										if (MathTools.isFinite(optrc3Energy)) {
											rc2Energy.mult(optrc3Energy);
										}
									}
								}

								optrc2Energy = opt.opt(optrc2Energy, rc2Energy.get());
							}

							rc1Energy.mult(optrc2Energy);
						}
					}

					pos1Energy = opt.opt(pos1Energy, rc1Energy.get());
				}

				assert (MathTools.isFinite(pos1Energy));
				energy.mult(pos1Energy);
			}

			return energy.get();
		}

		boolean isPruned(ConfIndex index, RCTuple tripleTuple, int pos1, int rc1) {

			if (pmat.getOneBody(pos1, rc1)) {
				return true;
			}

			tripleTuple.pos.set(2, pos1);
			tripleTuple.RCs.set(2, rc1);

			for (int i=0; i<index.numDefined; i++) {
				int pos2 = index.definedPos[i];
				int rc2 = index.definedRCs[i];

				if (pmat.getPairwise(pos1, rc1, pos2, rc2)) {
					return true;
				}

				tripleTuple.pos.set(1, pos2);
				tripleTuple.RCs.set(1, rc2);

				for (int j=0; j<i; j++) {
					int pos3 = index.definedPos[j];
					int rc3 = index.definedRCs[j];

					if (pmat.getPairwise(pos1, rc1, pos3, rc3)) {
						return true;
					}
					if (pmat.getPairwise(pos2, rc2, pos3, rc3)) {
						return true;
					}

					tripleTuple.pos.set(0, pos3);
					tripleTuple.RCs.set(0, rc3);

					if (pmat.getTuple(tripleTuple)) {
						return true;
					}
				}
			}

			return false;
		}

		/** WARNING: naive brute force method, for testing small trees only */
		Map<Sequence,BigInteger> countLeavesBySequence(ConfIndex index) {

			Map<Sequence,BigInteger> out = new HashMap<>();

			// NOTE: java has a hard time with recursive lambdas,
			// so use an array to work around the compiler's limitations
			Runnable[] f = { null };
			f[0] = () -> {

				// if this is a leaf node, increment the sequence counter
				if (index.isFullyDefined()) {
					out.compute(makeSeq(Conf.make(index)), (seq,  old) -> {
						if (old == null) {
							return BigInteger.ONE;
						} else {
							return old.add(BigInteger.ONE);
						}
					});
					return;
				}

				// otherwise, recurse
				int pos = index.numDefined;
				for (int rc : rcs.get(pos)) {
					index.assignInPlace(pos, rc);
					f[0].run();
					index.unassignInPlace(pos);
				}
			};
			f[0].run();

			return out;
		}

		BigIntegerBounds boundLeavesPerSequence(ConfIndex index) {

			BigIntegerBounds count = new BigIntegerBounds(BigInteger.ONE, BigInteger.ONE);

			for (int i=0; i<index.numUndefined; i++) {
				int pos = index.undefinedPos[i];

				int minCount = Integer.MAX_VALUE;
				int maxCount = 0;

				int numRts = numRtsByPos[pos];
				if (numRts > 0) {

					// count the RCs by RT
					int[] counts = new int[numRts];
					for (int rc : rcs.get(pos)) {
						int rtCount = ++counts[rtsByRcByPos[pos][rc]];
						minCount = Math.min(minCount, rtCount);
						maxCount = Math.max(maxCount, rtCount);
					}

				} else {

					// all RCs are the same RT
					minCount = rcs.getNum(pos);
					maxCount = minCount;
				}

				count.lower = count.lower.multiply(BigInteger.valueOf(minCount));
				count.upper = count.upper.multiply(BigInteger.valueOf(maxCount));
			}

			return count;
		}

		BigDecimal calcZ() {
			ConfIndex index = makeConfIndex();
			return calcZ(index, rcs, blute.factor);
		}

		BigDecimal calcZ(Sequence seq) {
			ConfIndex index = makeConfIndex();
			RCs rcs = seq.makeRCs(state.confSpace);
			return calcZ(index, rcs, blute.factor);
		}

		BigDecimal calcZ(ConfIndex index, RCs rcs, BigDecimal rootFactor) {

			// cannot work on leaf nodes
			assert (index.numDefined < index.numPos);

			BigDecimal z = BigDecimal.ZERO;

			// OPTIMIZATION: allocate one RCTuple instance for all the triple lookups
			// and set positions in 3-2-1 order so the positions are sorted in ascending order
			RCTuple tripleTuple = new RCTuple(0, 0, 0, 0, 0, 0);

			// pick the next pos to assign
			int pos = index.numDefined;

			for (int rc : rcs.get(pos)) {

				// get the zpart
				BigDecimal zpart;
				if (index.numDefined == 0) {
					zpart = rootFactor;
				} else {
					zpart = getZPart(index, tripleTuple, pos, rc);
				}

				// short circuit for efficiency
				if (MathTools.isZero(zpart)) {
					continue;
				}

				if (index.numDefined < index.numPos - 1) {

					// have positions left to assign, so recurse
					index.assignInPlace(pos, rc);
					z = bigMath()
						.set(calcZ(index, rcs, rootFactor))
						.mult(zpart)
						.add(z)
						.get();
					index.unassignInPlace(pos);

				} else {

					// all positions assigned, just add the zpart
					z = bigMath()
						.set(zpart)
						.add(z)
						.get();
				}
			}

			return z;
		}
	}
}
