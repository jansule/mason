package sim.field;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;

import mpi.MPI;
import mpi.MPIException;
import sim.util.DoublePoint;

public class DRemoteTransporter {

	int nc; // number of direct neighbors
	int[] src_count, src_displ, dst_count, dst_displ;

	HashMap<Integer, RemoteOutputStream> dstMap;
	RemoteOutputStream[] outputStreams;

	DPartition partition;
	int[] neighbors;

	// TODO: Should this be Steppable or Objects
	// what about multiple different types of agents?
	public ArrayList<Transportee> objectQueue;

	public DRemoteTransporter(DPartition partition) {
		this.partition = partition;
		reload();

		partition.registerPreCommit(arg -> {
			try {
				sync();
			} catch (MPIException | IOException | ClassNotFoundException e) {
				e.printStackTrace();
				System.exit(-1);
			}
		});

		partition.registerPostCommit(arg -> {
			reload();
			try {
				sync();
			} catch (MPIException | IOException | ClassNotFoundException e) {
				e.printStackTrace();
				System.exit(-1);
			}
		});
	}

	public void reload() {
		// TODO cannot work with one node?
		neighbors = partition.getNeighborIds();
		nc = neighbors.length;

		objectQueue = new ArrayList<>();

		src_count = new int[nc];
		src_displ = new int[nc];
		dst_count = new int[nc];
		dst_displ = new int[nc];

		// outputStreams for direct neighbors
		try {
			outputStreams = new RemoteOutputStream[nc];
			for (int i = 0; i < nc; i++)
				outputStreams[i] = new RemoteOutputStream();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}

		// neighbors
		dstMap = new HashMap<Integer, RemoteOutputStream>();
		for (int i = 0; i < nc; i++)
			dstMap.putIfAbsent(neighbors[i], outputStreams[i]);
	}

	public int size() {
		return objectQueue.size();
	}

	public void clear() {
		objectQueue.clear();
	}

	public void migrate(final Object obj, final int dst, DoublePoint loc) {
		// Wrap the agent, this is important because we want to keep track of
		// dst, which could be the diagonal processor
		Transportee wrapper = new Transportee(dst, obj, loc);
		assert dstMap.containsKey(dst);
		try {

//			if (wrapper.wrappedObject instanceof SelfStreamedAgent) {
//				// write header information, all agent has this info
//				writeHeader(dstMap.get(dst), wrapper);
//				// write agent
//				((SelfStreamedAgent) wrapper.wrappedObject).writeStream(dstMap.get(dst));
//				// have to flush the data, in case user forget this step
//				dstMap.get(dst).os.flush();
//			} else {
//			dstMap.get(dst).write(wrapper);
//			}
			dstMap.get(dst).write(wrapper);
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	public void sync() throws MPIException, IOException, ClassNotFoundException {
		// Prepare data
		for (int i = 0, total = 0; i < nc; i++) {
			outputStreams[i].flush();
			src_count[i] = outputStreams[i].size();
			src_displ[i] = total;
			total += src_count[i];
		}

		// Concat neighbor streams into one
		ByteArrayOutputStream objstream = new ByteArrayOutputStream();
		for (int i = 0; i < nc; i++)
			objstream.write(outputStreams[i].toByteArray());
		ByteBuffer sendbuf = ByteBuffer.allocateDirect(objstream.size());
		sendbuf.put(objstream.toByteArray()).flip();

		// First exchange count[] of the send byte buffers with neighbors so that we can
		// setup recvbuf
		partition.comm.neighborAllToAll(src_count, 1, MPI.INT, dst_count, 1, MPI.INT);
		for (int i = 0, total = 0; i < nc; i++) {
			dst_displ[i] = total;
			total += dst_count[i];
		}
		ByteBuffer recvbuf = ByteBuffer.allocateDirect(dst_displ[nc - 1] + dst_count[nc - 1]);

		// exchange the actual object bytes
		partition.comm.neighborAllToAllv(sendbuf, src_count, src_displ, MPI.BYTE, recvbuf, dst_count, dst_displ,
				MPI.BYTE);

		// read and handle incoming objects
		ArrayList<Transportee> bufferList = new ArrayList<Transportee>();
		for (int i = 0; i < nc; i++) {
			byte[] data = new byte[dst_count[i]];
			recvbuf.position(dst_displ[i]);
			recvbuf.get(data);
			ByteArrayInputStream in = new ByteArrayInputStream(data);
			ObjectInputStream is = new ObjectInputStream(in);
			boolean more = true;
			while (more) {
				try {
					Transportee wrapper = null;
					Object object = is.readObject();
//					if (object instanceof String) {
//						String className = (String) object;
//						// return the wrapper with header information filled in
//						wrapper = readHeader(is, className);
//						((SelfStreamedAgent) wrapper.wrappedObject).readStream(is);
//					} else {
//					wrapper = (Transportee) object;
//					}
					wrapper = (Transportee) object;
					if (partition.pid != wrapper.destination) {
						assert dstMap.containsKey(wrapper.destination);
						bufferList.add(wrapper);
					} else
						objectQueue.add(wrapper);
				} catch (EOFException e) {
					more = false;
				}
			}
		}

		// Clear previous queues
		for (int i = 0; i < nc; i++)
			outputStreams[i].reset();

		// Handling the agent in bufferList
		for (int i = 0; i < bufferList.size(); ++i) {
			Transportee wrapper = (Transportee) bufferList.get(i);
			int dst = wrapper.destination;
//			if (wrapper.wrappedObject instanceof SelfStreamedAgent) {
//				// write header information, all agent has this info
//				writeHeader(dstMap.get(dst), wrapper);
//				// write agent
//				((SelfStreamedAgent) wrapper.wrappedObject).writeStream(dstMap.get(dst));
//				// have to flush the data, in case user forget this step
//				dstMap.get(dst).os.flush();
//			} else {
//			dstMap.get(dst).write(wrapper);
//			}
			dstMap.get(dst).write(wrapper);
		}
		bufferList.clear();
	}

	public static class RemoteOutputStream {
		public ByteArrayOutputStream out;
		public ObjectOutputStream os;

		public RemoteOutputStream() throws IOException {
			out = new ByteArrayOutputStream();
			os = new ObjectOutputStream(out);
		}

		public void write(Object obj) throws IOException {
			os.writeObject(obj);
		}

		public byte[] toByteArray() {
			return out.toByteArray();
		}

		public int size() {
			return out.size();
		}

		public void flush() throws IOException {
			os.flush();
		}

		public void reset() throws IOException {
			os.close();
			out.close();
			out = new ByteArrayOutputStream();
			os = new ObjectOutputStream(out);
		}
	}

//	public void writeHeader(RemoteOutputStream aos, Transportee wrapper) throws IOException {
//		String className = wrapper.wrappedObject.getClass().getName();
//		aos.os.writeObject(className);
//		aos.os.writeInt(wrapper.destination);
//		aos.os.writeBoolean(wrapper.migrate);
//		// TODO, so far assume loc is Double Point 2D
//		aos.os.writeDouble(wrapper.loc.c[0]);
//		aos.os.writeDouble(wrapper.loc.c[1]);
//		aos.os.flush();
//	}
//
//	public Transportee readHeader(ObjectInputStream is, String className) throws IOException {
//		// read destination
//		int dst = is.readInt();
//		// read Wrapper data
//		boolean migrate = is.readBoolean();
//		// TODO, so far assume loc is Double Point 2D
//		double x = is.readDouble();
//		double y = is.readDouble();
//		// create the new agent
//		SelfStreamedAgent newAgent = null;
//		try {
//			newAgent = (SelfStreamedAgent) Class.forName(className).newInstance();
//		} catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
//			e.printStackTrace();
//		}
//		// read in the data
//		Transportee wrapper = new Transportee(dst, newAgent, new DoublePoint(x, y), migrate);
//		return wrapper;
//	}

}
