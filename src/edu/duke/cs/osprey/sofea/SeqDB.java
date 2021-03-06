package edu.duke.cs.osprey.sofea;

import edu.duke.cs.osprey.confspace.MultiStateConfSpace;
import edu.duke.cs.osprey.confspace.Sequence;
import edu.duke.cs.osprey.tools.*;
import edu.duke.cs.osprey.tools.MathTools.BigDecimalBounds;
import org.jetbrains.annotations.NotNull;
import org.mapdb.*;
import org.mapdb.serializer.GroupSerializerObjectArray;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.function.Consumer;


public class SeqDB implements AutoCloseable {

	private static boolean isEmptySum(BigDecimalBounds z) {
		return MathTools.isZero(z.lower) && MathTools.isZero(z.upper);
	}

	private static BigDecimalBounds makeEmptySum() {
		return new BigDecimalBounds(BigDecimal.ZERO, BigDecimal.ZERO);
	}

	public static class SeqInfo {

		public final BigDecimalBounds[] z;

		public SeqInfo(int size) {
			this.z = new BigDecimalBounds[size];
		}

		public void setEmpty() {
			for (int i = 0; i<z.length; i++) {
				z[i] = makeEmptySum();
			}
		}

		public boolean isEmpty() {
			for (BigDecimalBounds b : z) {
				if (!isEmptySum(b)) {
					return false;
				}
			}
			return true;
		}

		public BigDecimalBounds get(MultiStateConfSpace.State state) {
			return z[state.sequencedIndex];
		}

		@Override
		public int hashCode() {
			return HashCalculator.combineObjHashes(z);
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof SeqInfo && equals((SeqInfo)other);
		}

		public boolean equals(SeqInfo other) {
			return Arrays.equals(this.z, other.z);
		}

		@Override
		public String toString() {
			return Streams.joinToString(z, ", ", b -> Log.formatBigLn(b));
		}
	}

	private static abstract class SimpleSerializer<T> extends GroupSerializerObjectArray<T> {

		public final int fixedSize;

		protected SimpleSerializer() {
			// dynamic size, rather than fixed
			this(-1);
		}

		protected SimpleSerializer(int fixedSize) {
			this.fixedSize = fixedSize;
		}

		@Override
		public boolean isTrusted() {
			// we always read/write the same number of bytes, so we're "trusted" by MapDB
			return true;
		}

		@Override
		public int fixedSize() {
			return fixedSize;
		}
	}

	// NOTE: all int values get +1 when serialized, so we can accomodate the range [-1,maxVal]
	private static class IntArraySerializer extends SimpleSerializer<int[]> {

		private final IntEncoding encoding;
		private final int numPos;

		public IntArraySerializer(int maxVal, int numPos) {
			this(IntEncoding.get(maxVal + 1), numPos);
		}

		private IntArraySerializer(IntEncoding encoding, int numPos) {
			super(encoding.numBytes*numPos);
			this.encoding = encoding;
			this.numPos = numPos;
		}

		@Override
		public void serialize(@NotNull DataOutput2 out, @NotNull int[] data)
		throws IOException {
			assert (data.length == numPos);
			for (int i=0; i<numPos; i++) {
				encoding.write(out, data[i] + 1);
			}
		}

		@Override
		public int[] deserialize(@NotNull DataInput2 in, int available)
		throws IOException {
			int[] data = new int[numPos];
			for (int i=0; i<numPos; i++) {
				data[i] = encoding.read(in) - 1;
			}
			return data;
		}

		@Override
		public int compare(int[] a, int[] b) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean equals(int[] a, int[] b) {
			return Arrays.equals(a, b);
		}

		@Override
		public int hashCode(@NotNull int[] data, int seed) {
			return DataIO.intHash(Arrays.hashCode(data) + seed);
		}
	}

	private static class BigDecimalSerializer extends SimpleSerializer<BigDecimal> {

		private BigDecimalIO io = new BigDecimalIO.Variable();

		@Override
		public void serialize(@NotNull DataOutput2 out, @NotNull BigDecimal data)
		throws IOException {
			io.write(out, data);
		}

		@Override
		public BigDecimal deserialize(@NotNull DataInput2 in, int available)
		throws IOException {
			return io.read(in);
		}

		@Override
		public int compare(BigDecimal a, BigDecimal b) {
			return MathTools.compare(a, b);
		}

		@Override
		public boolean equals(BigDecimal a, BigDecimal b) {
			return MathTools.isSameValue(a, b);
		}

		@Override
		public int hashCode(@NotNull BigDecimal data, int seed) {
			return DataIO.intHash(data.hashCode() + seed);
		}
	}

	private static class BigDecimalBoundsSerializer extends SimpleSerializer<BigDecimalBounds> {

		private final BigDecimalSerializer s = new BigDecimalSerializer();

		@Override
		public void serialize(@NotNull DataOutput2 out, @NotNull BigDecimalBounds data)
		throws IOException {
			s.serialize(out, data.lower);
			s.serialize(out, data.upper);
		}

		@Override
		public BigDecimalBounds deserialize(@NotNull DataInput2 in, int available)
		throws IOException {
			return new BigDecimalBounds(
				s.deserialize(in, available),
				s.deserialize(in, available)
			);
		}

		@Override
		public int compare(BigDecimalBounds a, BigDecimalBounds b) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean equals(BigDecimalBounds a, BigDecimalBounds b) {
			return a.equals(b);
		}

		@Override
		public int hashCode(@NotNull BigDecimalBounds data, int seed) {
			return DataIO.intHash(data.hashCode() + seed);
		}
	}

	private static class SeqInfoSerializer extends SimpleSerializer<SeqInfo> {

		private final int numBounds;

		private final BigDecimalBoundsSerializer s = new BigDecimalBoundsSerializer();

		public SeqInfoSerializer(int numBounds) {
			this.numBounds = numBounds;
		}

		@Override
		public void serialize(@NotNull DataOutput2 out, @NotNull SeqInfo data)
		throws IOException {
			for (int i=0; i<numBounds; i++) {
				s.serialize(out, data.z[i]);
			}
		}

		@Override
		public SeqInfo deserialize(@NotNull DataInput2 in, int available)
		throws IOException {
			SeqInfo data = new SeqInfo(numBounds);
			for (int i=0; i<numBounds; i++) {
				data.z[i] = s.deserialize(in, available);
			}
			return data;
		}

		@Override
		public int compare(SeqInfo a, SeqInfo b) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean equals(SeqInfo a, SeqInfo b) {
			return a.equals(b);
		}

		@Override
		public int hashCode(@NotNull SeqInfo data, int seed) {
			return DataIO.intHash(data.hashCode() + seed);
		}
	}


	public final MultiStateConfSpace confSpace;
	public final MathContext mathContext;
	public final File file;

	private final DB db;
	private final HTreeMap<int[],SeqInfo> sequencedSums;
	private final HTreeMap<Integer,BigDecimalBounds> unsequencedSums;

	public SeqDB(MultiStateConfSpace confSpace, MathContext mathContext) {
		this(confSpace, mathContext, null);
	}

	public SeqDB(MultiStateConfSpace confSpace, MathContext mathContext, File file) {

		this.confSpace = confSpace;
		this.mathContext = mathContext;
		this.file = file;

		// open the DB
		if (file != null) {
			db = DBMaker.fileDB(file)
				.fileMmapEnableIfSupported() // use memory-mapped files if possible (can be much faster)
				// TODO: optimize allocation scheme? https://jankotek.gitbooks.io/mapdb/content/performance/
				.closeOnJvmShutdown()
				.make();
		} else {
			db = DBMaker.memoryDB()
				.make();
		}

		// open the tables

		sequencedSums = db.hashMap("sequenced-sums")
			.keySerializer(new IntArraySerializer(
				confSpace.seqSpace.positions.stream()
					.flatMap(pos -> pos.resTypes.stream())
					.mapToInt(resType -> resType.index)
					.max()
					.orElse(-1),
				confSpace.seqSpace.positions.size()
			))
			.valueSerializer(new SeqInfoSerializer(confSpace.sequencedStates.size()))
			.createOrOpen();

		unsequencedSums = db.hashMap("unsequenced-sums")
			.keySerializer(Serializer.INTEGER)
			.valueSerializer(new BigDecimalBoundsSerializer())
			.createOrOpen();
	}

	private BigMath bigMath() {
		return new BigMath(mathContext);
	}


	public class Transaction {

		private final Map<Sequence,SeqInfo> sequencedSums = new HashMap<>();
		private final Map<Integer,BigDecimalBounds> unsequencedSums = new HashMap<>();
		private boolean isEmpty = true;

		private Transaction() {
			// keep the constructor private
		}

		private void updateZSum(MultiStateConfSpace.State state, Sequence seq, Consumer<BigDecimalBounds> f) {

			if (state.isSequenced) {

				// get the tx seq info, or empty sums
				SeqInfo seqInfo = sequencedSums.get(seq);
				if (seqInfo == null) {
					seqInfo = new SeqInfo(confSpace.sequencedStates.size());
					seqInfo.setEmpty();
				}

				f.accept(seqInfo.get(state));
				sequencedSums.put(seq, seqInfo);

			} else {

				// get the tx sum, or empty
				BigDecimalBounds sum = unsequencedSums.get(state.unsequencedIndex);
				if (sum == null) {
					sum = new BigDecimalBounds(BigDecimal.ZERO, BigDecimal.ZERO);
				}

				f.accept(sum);
				unsequencedSums.put(state.unsequencedIndex, sum);

			}

			isEmpty = false;
		}

		public void addZ(MultiStateConfSpace.State state, Sequence seq, BigDecimal z) {

			if (!MathTools.isFinite(z)) {
				throw new IllegalArgumentException("Z must be finite: " + z);
			}

			updateZSum(state, seq, sum -> {
				sum.lower = bigMath()
					.set(sum.lower)
					.add(z)
					.get();
				sum.upper = bigMath()
					.set(sum.upper)
					.add(z)
					.get();
			});
		}

		public void addZ(MultiStateConfSpace.State state, Sequence seq, BigDecimalBounds z) {

			if (!MathTools.isFinite(z.lower) || !MathTools.isFinite(z.upper)) {
				throw new IllegalArgumentException("Z must be finite: " + z);
			}

			updateZSum(state, seq, sum -> {
				sum.lower = bigMath()
					.set(sum.lower)
					.add(z.lower)
					.get();
				sum.upper = bigMath()
					.set(sum.upper)
					.add(z.upper)
					.get();
			});
		}

		public void subZ(MultiStateConfSpace.State state, Sequence seq, BigDecimalBounds z) {

			if (!MathTools.isFinite(z.lower) || !MathTools.isFinite(z.upper)) {
				throw new IllegalArgumentException("Z must be finite: " + z);
			}

			updateZSum(state, seq, sum -> {
				sum.lower = bigMath()
					.set(sum.lower)
					.sub(z.lower)
					.get();
				sum.upper = bigMath()
					.set(sum.upper)
					.sub(z.upper)
					.get();
			});
		}

		public boolean isEmpty() {
			return isEmpty;
		}

		private void combineSums(BigDecimalBounds sum, BigDecimalBounds oldSum) {
			sum.upper = bigMath()
				.set(sum.upper)
				.add(oldSum.upper)
				.atLeast(0.0) // NOTE: roundoff error can cause this to drop below 0
				.get();
			sum.lower = bigMath()
				.set(sum.lower)
				.add(oldSum.lower)
				.atMost(sum.upper) // don't exceed the upper value due to roundoff error
				.get();
		}

		public void commit() {

			// short circuit
			if (isEmpty) {
				return;
			}

			// push writes to the db
			for (Map.Entry<Sequence,SeqInfo> entry : sequencedSums.entrySet()) {
				Sequence seq = entry.getKey();
				SeqInfo seqInfo = entry.getValue();

				// combine with the old sums if needed
				SeqInfo oldSeqInfo = SeqDB.this.sequencedSums.get(seq.rtIndices);
				if (oldSeqInfo != null) {
					for (MultiStateConfSpace.State state : confSpace.sequencedStates) {
						BigDecimalBounds sum = seqInfo.z[state.sequencedIndex];
						BigDecimalBounds oldSum = oldSeqInfo.z[state.sequencedIndex];
						combineSums(sum, oldSum);
					}
				}

				SeqDB.this.sequencedSums.put(seq.rtIndices, seqInfo);
			}

			for (Map.Entry<Integer,BigDecimalBounds> entry : unsequencedSums.entrySet()) {
				int unsequencedIndex = entry.getKey();
				BigDecimalBounds sum = entry.getValue();

				// combine with the old sum if needed
				BigDecimalBounds oldSum = SeqDB.this.unsequencedSums.get(unsequencedIndex);
				if (oldSum != null) {
					combineSums(sum, oldSum);
				}

				SeqDB.this.unsequencedSums.put(unsequencedIndex, sum);
			}

			db.commit();

			// reset state
			sequencedSums.clear();
			unsequencedSums.clear();
			isEmpty = true;
		}
	}

	public Transaction transaction() {
		return new Transaction();
	}

	@Override
	public void close() {
		db.close();
	}

	/**
	 * returns accumulated Z values for the queried state
	 * (you probably don't want this unless you're debugging)
	 */
	public BigDecimalBounds getUnsequencedSum(MultiStateConfSpace.State state) {
		BigDecimalBounds z = unsequencedSums.get(state.unsequencedIndex);
		if (z == null) {
			z = makeEmptySum();
		}
		return z;
	}

	/**
	 * returns the current Z bounds for the queried state
	 */
	public BigDecimalBounds getUnsequencedBound(MultiStateConfSpace.State state) {
		BigDecimalBounds z = unsequencedSums.get(state.unsequencedIndex);
		if (z == null) {
			z = new BigDecimalBounds(BigDecimal.ZERO, MathTools.BigPositiveInfinity);
		}
		return z;
	}

	/**
	 * returns accumulated state Z values for the queried sequence
	 * does not include Z uncertainty from ancestral partial sequences
	 * (you probably don't want this unless you're debugging)
	 */
	public SeqInfo getSequencedSums(Sequence seq) {
		SeqInfo seqInfo = sequencedSums.get(seq.rtIndices);
		if (seqInfo == null) {
			seqInfo = new SeqInfo(confSpace.sequencedStates.size());
			seqInfo.setEmpty();
		}
		return seqInfo;
	}

	/**
	 * returns accumulated state Z values for all sequences
	 */
	public Iterable<Map.Entry<Sequence,SeqInfo>> getSequencedSums() {

		return () -> new Iterator<Map.Entry<Sequence,SeqInfo>>() {

			Iterator<Map.Entry<int[],SeqInfo>> iter = sequencedSums.getEntries().iterator();

			@Override
			public boolean hasNext() {
				return iter.hasNext();
			}

			@Override
			public Map.Entry<Sequence, SeqInfo> next() {

				Map.Entry<int[],SeqInfo> entry = iter.next();

				Sequence seq = new Sequence(confSpace.seqSpace, entry.getKey());
				SeqInfo seqInfo = entry.getValue();

				return new AbstractMap.SimpleEntry<>(seq, seqInfo);
			}
		};
	}

	/**
	 * returns the current state Z bounds for the queried sequence
	 * bounds for partial sequences only describe *unexplored* subtrees
	 * as more subtrees get explored, those Z values will be transfered to more fully-assigned sequences
	 * bounds for fully-explored partial sequences will be zero
	 */
	public SeqInfo getSequencedBounds(Sequence seq) {
		SeqInfo seqInfo = getSequencedSums(seq);
		if (seq.isFullyAssigned()) {
			addZAncestry(seq, seqInfo);
		}
		return seqInfo;
	}

	/**
	 * returns Z bounds for all sequences
	 * returns bounds for both full and partial sequences
	 */
	public Iterable<Map.Entry<Sequence,SeqInfo>> getSequencedBounds() {
		return () -> new Iterator<Map.Entry<Sequence,SeqInfo>>() {

			Iterator<Map.Entry<int[],SeqInfo>> iter = sequencedSums.getEntries().iterator();

			@Override
			public boolean hasNext() {
				return iter.hasNext();
			}

			@Override
			public Map.Entry<Sequence,SeqInfo> next() {

				Map.Entry<int[],SeqInfo> entry = iter.next();

				Sequence seq = new Sequence(confSpace.seqSpace, entry.getKey());
				SeqInfo seqInfo = entry.getValue();

				if (seq.isFullyAssigned()) {
					addZAncestry(seq, seqInfo);
				}

				return new AbstractMap.SimpleEntry<>(seq, seqInfo);
			}
		};
	}

	private void addZAncestry(Sequence seq, SeqInfo seqInfo) {

		int[] rtIndices = new int[confSpace.seqSpace.positions.size()];
		System.arraycopy(seq.rtIndices, 0, rtIndices, 0, rtIndices.length);

		// add uncertainty from partial sequence ancestry
		// NOTE: assumes tree pos order follows seq pos order
		for (int i=rtIndices.length - 1; i>=0; i--) {
			rtIndices[i] = Sequence.Unassigned;

			SeqInfo parentSeqInfo = sequencedSums.get(rtIndices);
			if (parentSeqInfo != null) {
				// couldn't that unexplored subtree contain no confs for this seq?
				// NOTE: don't add the lower bounds, the subtree need not necessarily contain confs for this sequence
				for (MultiStateConfSpace.State state : confSpace.sequencedStates) {
					seqInfo.z[state.sequencedIndex].upper = bigMath()
						.set(seqInfo.z[state.sequencedIndex].upper)
						.add(parentSeqInfo.z[state.sequencedIndex].upper)
						.get();
				}
			}
		}
	}
}
