package sim.field.grid;

import java.rmi.RemoteException;

import sim.engine.DSimState;
import sim.field.DPartition;
//import sim.field.DNonUniformPartition;
import sim.field.HaloField;
import sim.field.storage.IntGridStorage;
import sim.util.IntPoint;

public class NIntGrid2D extends HaloField<Integer, IntPoint> {
	public final int initVal;

	public NIntGrid2D(final DPartition ps, final int[] aoi, final int initVal, final DSimState state) {
		super(ps, aoi, new IntGridStorage(ps.getPartition(), initVal), state);
		if (numDimensions != 2)
			throw new IllegalArgumentException("The number of dimensions is expected to be 2, got: " + numDimensions);
		this.initVal = initVal;
	}

	public int[] getStorageArray() {
		return (int[]) field.getStorage();
	}

	public Integer getRMI(final IntPoint p) throws RemoteException {
		if (!inLocal(p))
			throw new RemoteException(
					"The point " + p + " does not exist in this partition " + partition.getPid() + " " + partition.getPartition());

		return getStorageArray()[field.getFlatIdx(toLocalPoint(p))];
	}

	public final int get(final int x, final int y) {
		return get(new IntPoint(x, y));
	}

	public final int get(final IntPoint p) {
		if (!inLocalAndHalo(p)) {
			System.out.println(String.format("PID %d get %s is out of local boundary, accessing remotely through RMI",
					partition.getPid(), p.toString()));
			try {
				return (int) getFromRemote(p);
			} catch (final RemoteException e) {
				e.printStackTrace();
				System.exit(-1);

			}
		}

		return getStorageArray()[field.getFlatIdx(toLocalPoint(p))];
	}

	public void addObject(final IntPoint p, final int val) {
		// In this partition but not in ghost cells
		if (!inLocal(p))
			throw new IllegalArgumentException(
					String.format("PID %d set %s is out of local boundary", partition.getPid(), p.toString()));

		getStorageArray()[field.getFlatIdx(toLocalPoint(p))] = val;
	}

	public void addObject(final IntPoint p, final Integer val) {
		addObject(p, val.intValue());
	}

	public void removeObject(final IntPoint p, final Integer t) {
		removeObject(p);
	}

	// Overloading to prevent AutoBoxing-UnBoxing
	public void removeObject(final IntPoint p, final int t) {
		removeObject(p);
	}

	public void removeObject(final IntPoint p) {
		addObject(p, initVal);
	}

	// Overloading to prevent AutoBoxing-UnBoxing
	public void moveObject(final IntPoint fromP, final IntPoint toP, final int t) {
		removeObject(fromP);
		addObject(toP, t);
	}

	/*
	 * public static void main(String[] args) throws MPIException, IOException {
	 * MPI.Init(args);
	 *
	 * int[] aoi = new int[] {2, 2}; int[] size = new int[] {10, 10};
	 *
	 * DNonUniformPartition p = DNonUniformPartition.getPartitionScheme(size, true,
	 * aoi); p.initUniformly(null); p.commit();
	 *
	 * NIntGrid2D f = new NIntGrid2D(p, aoi, p.getPid());
	 *
	 * f.sync();
	 *
	 * MPITest.execInOrder(i -> System.out.println(f), 500);
	 *
	 * MPITest.execOnlyIn(0, i -> System.out.println("Testing RMI remote calls"));
	 * sim.field.RemoteProxy.Init(0); f.initRemote();
	 *
	 * // Choose the points that are out of halo area int pid = p.getPid(); int x =
	 * f.stx(2 + 5 * ((pid + 1) / 2)); int y = f.sty(2 + 5 * ((pid + 1) % 2));
	 * MPITest.execInOrder(i ->
	 * System.out.println(String.format("PID %d accessing <%d, %d> result %d", i, x,
	 * y, f.get(x, y))), 200);
	 *
	 * sim.field.RemoteProxy.Finalize(); MPI.Finalize(); }
	 */
}
