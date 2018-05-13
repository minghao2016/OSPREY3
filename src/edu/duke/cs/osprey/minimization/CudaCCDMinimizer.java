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

package edu.duke.cs.osprey.minimization;

import java.io.IOException;

import cern.colt.matrix.DoubleFactory1D;
import cern.colt.matrix.DoubleMatrix1D;
import edu.duke.cs.osprey.gpu.cuda.GpuStream;
import edu.duke.cs.osprey.gpu.cuda.GpuStreamPool;
import edu.duke.cs.osprey.gpu.cuda.kernels.CCDKernelCuda;

public class CudaCCDMinimizer implements Minimizer.NeedsCleanup, Minimizer.Reusable {
	
	private GpuStreamPool streams;
	private ObjectiveFunction f;
	private GpuStream stream;
	private CCDKernelCuda kernel;
	private ObjectiveFunction.DofBounds dofBounds;

	public CudaCCDMinimizer(GpuStreamPool streams) {
		this.streams = streams;
	}
	
	public CudaCCDMinimizer(GpuStreamPool streams, ObjectiveFunction f) {
		this(streams);
		init(f);
	}
	
	@Override
	public void init(ObjectiveFunction f) {
		
		this.f = f;
		
		if (kernel == null) {
			// make the kernel
			try {
				stream = streams.checkout();
				kernel = new CCDKernelCuda(stream);
			} catch (IOException ex) {
				streams.release(stream);
				stream = null;
				throw new Error("can't make CCD kernel", ex);
			}
		}
		
		// get the molecule objective function
		if (f instanceof MoleculeModifierAndScorer) {
			kernel.init(new MoleculeObjectiveFunction((MoleculeModifierAndScorer)f));
		} else if (f instanceof MoleculeObjectiveFunction) {
			kernel.init((MoleculeObjectiveFunction)f);
		} else {
			throw new Error("objective function should be a " + MoleculeModifierAndScorer.class.getSimpleName() + ", not a " + f.getClass().getSimpleName() + ". this is a bug");
		}

		dofBounds = new ObjectiveFunction.DofBounds(f.getConstraints());
	}

	@Override
	public Minimizer.Result minimizeFromCenter() {

		DoubleMatrix1D x = DoubleFactory1D.dense.make(dofBounds.size());
		dofBounds.getCenter(x);

		return minimizeFrom(x);
	}

	@Override
	public Minimizer.Result minimizeFrom(DoubleMatrix1D x) {
		
		// do the minimization
		f.setDOFs(x);
		kernel.uploadCoordsAsync();
		Minimizer.Result result = kernel.runSync(x, dofBounds);
		
		// update the CPU-side molecule
		f.setDOFs(result.dofValues);
		
		return result;
	}

	@Override
	public void clean() {
		if (kernel != null) {
			kernel.cleanup();
			kernel = null;
		}
		streams.release(stream);
	}
}
