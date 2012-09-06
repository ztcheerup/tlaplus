// Copyright (c) 2003 Compaq Corporation.  All rights reserved.
// Portions Copyright (c) 2003 Microsoft Corporation.  All rights reserved.
// Last modified on Mon 30 Apr 2007 at 13:18:29 PST by lamport
//      modified on Sat Aug  4 01:11:06 PDT 2001 by yuanyu

package tlc2.tool.distributed;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tlc2.TLCGlobals;
import tlc2.output.EC;
import tlc2.output.MP;
import tlc2.tool.ModelChecker;
import tlc2.tool.TLCState;
import tlc2.tool.TLCTrace;
import tlc2.tool.WorkerException;
import tlc2.tool.distributed.fp.FPSetManager;
import tlc2.tool.distributed.fp.FPSetRMI;
import tlc2.tool.distributed.fp.IFPSetManager;
import tlc2.tool.distributed.fp.NonDistributedFPSetManager;
import tlc2.tool.distributed.management.TLCServerMXWrapper;
import tlc2.tool.distributed.selector.BlockSelectorFactory;
import tlc2.tool.distributed.selector.IBlockSelector;
import tlc2.tool.fp.FPSet;
import tlc2.tool.management.TLCStandardMBean;
import tlc2.tool.queue.DiskStateQueue;
import tlc2.tool.queue.IStateQueue;
import tlc2.util.FP64;
import util.Assert;
import util.FileUtil;
import util.SimpleFilenameToStream;
import util.UniqueString;

@SuppressWarnings("serial")
public class TLCServer extends UnicastRemoteObject implements TLCServerRMI,
		InternRMI {

	/**
	 * Prefix master and worker heavy workload threads with this prefix and an incrementing counter to
	 * make the threads identifiable in jmx2munin statistics, which uses simple string matching.  
	 */
	public static final String THREAD_NAME_PREFIX = "TLCWorkerThread-";

	/**
	 * Used by TLCStatistics which are collected after the {@link FPSet} or {@link FPSetManager} shut down.
	 */
	static long finalNumberOfDistinctStates = -1L;
	
	/**
	 * the port # for tlc server
	 */
	public static int Port = Integer.getInteger(TLCServer.class.getName() + ".port", 10997);

	/**
	 * show statistics every 1 minutes
	 */
	private static final int REPORT_INTERVAL = Integer.getInteger(TLCServer.class.getName() + ".report", 1 * 60 * 1000);

	/**
	 * If the state/ dir should be cleaned up after a successful model run
	 */
	private static final boolean VETO_CLEANUP = Boolean.getBoolean(TLCServer.class.getName() + ".vetoCleanup");

	/**
	 * The amount of FPset servers to use (use a non-distributed FPSet server on
	 * master node if unset).
	 */
	private static final int expectedFPSetCount = Integer.getInteger(TLCServer.class.getName() + ".expectedFPSetCount", 0);

	/**
	 * Performance metric: distinct states per minute
	 */
	private long distinctStatesPerMinute;
	/**
	 * Performance metric: states per minute
	 */
	private long statesPerMinute;

	/**
	 * A thread pool used to execute tasks
	 */
	private final ExecutorService es = Executors.newCachedThreadPool();
	
	public final IFPSetManager fpSetManager;
	public final IStateQueue stateQueue;
	public final TLCTrace trace;

	private final DistApp work;
	private final String metadir;
	private final String filename;

	private TLCState errState = null;
	private boolean done = false;
	private boolean keepCallStack = false;
	
	/**
	 * Main data structure used to maintain the list of active workers (ref
	 * {@link TLCWorkerRMI}) and the corresponding local {@link TLCServerThread}
	 * .
	 * <p>
	 * A worker ({@link TLCWorkerRMI}) requires a local thread counterpart to do
	 * its work concurrently.
	 * <p>
	 * The implementation uses a {@link ConcurrentHashMap}, to allow concurrent
	 * access during the end game phase. It is the phase when
	 * {@link TLCServer#modelCheck()} cleans up threadsToWorkers by waiting
	 * {@link Thread#join()} on the various {@link TLCServerThread}s. If this
	 * action is overlapped with a worker registering - calling
	 * {@link TLCServer#registerWorker(TLCWorkerRMI)} - which would cause a
	 * {@link ConcurrentModificationException}.
	 */
	private final Map<TLCServerThread, TLCWorkerRMI> threadsToWorkers = new ConcurrentHashMap<TLCServerThread, TLCWorkerRMI>();
	
	private final IBlockSelector blockSelector;
	
	/**
	 * @param work
	 * @throws IOException
	 * @throws NotBoundException
	 */
	public TLCServer(TLCApp work) throws IOException, NotBoundException {
	    // LL modified error message on 7 April 2012
		Assert.check(work != null, "TLC server found null work.");

		// TLCApp which calculates the next state relation
		this.metadir = work.getMetadir();
		int end = this.metadir.length();
		if (this.metadir.endsWith(FileUtil.separator))
			end--;
		int start = this.metadir.lastIndexOf(FileUtil.separator, end - 1);
		this.filename = this.metadir.substring(start + 1, end);
		this.work = work;

		// State Queue of unexplored states
		this.stateQueue = new DiskStateQueue(this.metadir);

		// State trace file
		this.trace = new TLCTrace(this.metadir, this.work.getFileName(),
				this.work);

		// FPSet
		this.fpSetManager = getFPSetManagerImpl(work, metadir, expectedFPSetCount);
		
		// Determines the size of the state queue subset handed out to workers
		blockSelector = BlockSelectorFactory.getBlockSelector(this);
	}
	
	/**
	 * The {@link IFPSetManager} implementation to be used by the
	 * {@link TLCServer} implementation. Subclass may want to return specialized
	 * {@link IFPSetManager}s with different functionality.
	 * @param expectedfpsetcount2 
	 */
	protected IFPSetManager getFPSetManagerImpl(final TLCApp work,
			final String metadir, final int fpsetCount) throws IOException {
		// A single FPSet server running on the master node
		final FPSet fpSet = FPSet.getFPSet(work.getFPBits(),
				work.getFpMemSize());
		fpSet.init(0, metadir, work.getFileName());
		return new NonDistributedFPSetManager(fpSet, InetAddress.getLocalHost()
				.getCanonicalHostName());
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.TLCServerRMI#getCheckDeadlock()
	 */
	public final Boolean getCheckDeadlock() {
		return this.work.getCheckDeadlock();
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.TLCServerRMI#getPreprocess()
	 */
	public final Boolean getPreprocess() {
		return this.work.getPreprocess();
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.TLCServerRMI#getFPSetManager()
	 */
	public IFPSetManager getFPSetManager() {
		return this.fpSetManager;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.TLCServerRMI#getIrredPolyForFP()
	 */
	public final long getIrredPolyForFP() {
		return FP64.getIrredPoly();
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.InternRMI#intern(java.lang.String)
	 */
	public final UniqueString intern(String str) {
		// SZ 11.04.2009: changed access method
		return UniqueString.uniqueStringOf(str);
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.TLCServerRMI#registerWorker(tlc2.tool.distributed.TLCWorkerRMI)
	 */
	public synchronized final void registerWorker(TLCWorkerRMI worker
			) throws IOException {
		
		// Wake up potentially stuck TLCServerThreads (in
		// tlc2.tool.queue.StateQueue.isAvail()) to avoid a deadlock.
		// Obviously stuck TLCServerThreads will never be reported to 
		// users if resumeAllStuck() is not call by a new worker.
		stateQueue.resumeAllStuck();
		
		// create new server thread for given worker
		final TLCServerThread thread = new TLCServerThread(worker, this, es, blockSelector);
		threadsToWorkers.put(thread, worker);
		fpSetManager.addThread();
		thread.start();

		MP.printMessage(EC.TLC_DISTRIBUTED_WORKER_REGISTERED, worker.getURI().toString());
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.TLCServerRMI#registerFPSet(tlc2.tool.distributed.fp.FPSetRMI, java.lang.String)
	 */
	public synchronized void registerFPSet(FPSetRMI fpSet, String hostname) throws RemoteException {
		throw new UnsupportedOperationException("Not applicable for non-distributed TLCServer");
	}

	/**
	 * An (idempotent) method to remove a (dead) TLCServerThread from the TLCServer.
	 * 
	 * @see Map#remove(Object)
	 * @param thread
	 * @return 
	 */
	public TLCWorkerRMI removeTLCServerThread(final TLCServerThread thread) {
		final TLCWorkerRMI worker = threadsToWorkers.remove(thread);
		/*
		 * Only ever report a disconnected worker once!
		 * 
		 * Calling this method twice happens when the exception handling in
		 * TLCServerThread#run detects a disconnect server and the
		 * TLCServerThread#TimerTask (who periodically checks worker aliveness)
		 * again.
		 * 
		 * (TimerTask cancellation in TLCServerThread#run has a small chance of
		 * leaving the TimerTask running. This occurs by design if the TimerTask
		 * has already been marked for execution)
		 * 
		 * @see https://bugzilla.tlaplus.net/show_bug.cgi?id=216
		 */
		if (worker != null) {
			MP.printMessage(EC.TLC_DISTRIBUTED_WORKER_DEREGISTERED, thread.getUri().toString());
		}
		return worker;
	}

	/**
	 * @param s
	 * @param keep
	 * @return true iff setting the error state has succeeded. This is the case
	 *         for the first worker to call
	 *         {@link TLCServer#setErrState(TLCState, boolean)}. Subsequent
	 *         calls by other workers will be ignored. This implies that other
	 *         error states are ignored.
	 */
	public synchronized final boolean setErrState(TLCState s, boolean keep) {
		if (this.done) {
			return false;
		}
		this.done = true;
		this.errState = s;
		this.keepCallStack = keep;
		return true;
	}

	/**
	 * Indicates the completion of model checking. This is called by
	 * {@link TLCServerThread}s once they find an empty {@link IStateQueue}. An
	 * empty {@link IStateQueue} is the termination condition.
	 */
	public final void setDone() {
		this.done = true;
	}

	/**
	 * Creates a checkpoint for the currently running model run
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void checkpoint() throws IOException, InterruptedException {
		if (this.stateQueue.suspendAll()) {
			// Checkpoint:
			MP.printMessage(EC.TLC_CHECKPOINT_START, "-- Checkpointing of run " + this.metadir
					+ " compl");

			// start checkpointing:
			this.stateQueue.beginChkpt();
			this.trace.beginChkpt();
			this.fpSetManager.checkpoint(this.filename);
			this.stateQueue.resumeAll();
			UniqueString.internTbl.beginChkpt(this.metadir);
			// commit:
			this.stateQueue.commitChkpt();
			this.trace.commitChkpt();
			UniqueString.internTbl.commitChkpt(this.metadir);
			this.fpSetManager.commitChkpt();
			MP.printMessage(EC.TLC_CHECKPOINT_END, "eted.");
		}
	}

	/**
	 * Recovers a model run
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public final void recover() throws IOException, InterruptedException {
		this.trace.recover();
		this.stateQueue.recover();
		this.fpSetManager.recover(this.filename);
	}

	/**
	 * @throws Exception
	 */
	private final Set<Long> doInit() throws Exception {
		final SortedSet<Long> set = new TreeSet<Long>();
		TLCState curState = null;
		try {
			TLCState[] initStates = work.getInitStates();
			for (int i = 0; i < initStates.length; i++) {
				curState = initStates[i];
				boolean inConstraints = work.isInModel(curState);
				boolean seen = false;
				if (inConstraints) {
					long fp = curState.fingerPrint();
					seen = !set.add(fp);
					if (!seen) {
						initStates[i].uid = trace.writeState(fp);
						stateQueue.enqueue(initStates[i]);
					}
				}
				if (!inConstraints || !seen) {
					work.checkState(null, curState);
				}
			}
		} catch (Exception e) {
			this.errState = curState;
			this.keepCallStack = true;
			if (e instanceof WorkerException) {
				this.errState = ((WorkerException) e).state2;
				this.keepCallStack = ((WorkerException) e).keepCallStack;
			}
			this.done = true;
			throw e;
		}
		return set;
	}

	/**
	 * @param cleanup
	 * @throws IOException
	 */
	public final void close(boolean cleanup) throws IOException {
		this.trace.close();
		this.fpSetManager.close(cleanup);
		if (cleanup && !VETO_CLEANUP) {
			FileUtil.deleteDir(new File(this.metadir), true);
		}
	}

	/**
	 * @param server
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws NotBoundException
	 */
	protected void modelCheck() throws IOException, InterruptedException, NotBoundException {
		/*
		 * Before we initialize the server, we check if recovery is requested 
		 */

		boolean recovered = false;
		if (work.canRecover()) {
            MP.printMessage(EC.TLC_CHECKPOINT_RECOVER_START, metadir);
			recover();
			MP.printMessage(EC.TLC_CHECKPOINT_RECOVER_END, new String[] { String.valueOf(fpSetManager.size()),
                    String.valueOf(stateQueue.size())});
			recovered = true;
		}
		
		/*
		 * Start initializing the server by calculating the init state(s)
		 */

		//TODO if init states is huge, this might go OOM
		Set<Long> initFPs = new TreeSet<Long>();
		if (!recovered) {
			// Initialize with the initial states:
			try {
                MP.printMessage(EC.TLC_COMPUTING_INIT);
                initFPs = doInit();
				MP.printMessage(EC.TLC_INIT_GENERATED1,
						new String[] { String.valueOf(stateQueue.size()), "(s)" });
			} catch (Throwable e) {
				// Assert.printStack(e);
				done = true;
				// LL modified error message on 7 April 2012
				MP.printError(EC.GENERAL, "initializing the server", e); // LL changed call 7 April 2012
				if (errState != null) {
					MP.printMessage(EC.TLC_INITIAL_STATE, "While working on the initial state: " + errState);
				}
				// We redo the work on the error state, recording the call
				// stack.
				work.setCallStack();
				try {
					initFPs = doInit();
				} catch (Throwable e1) {
					MP.printError(EC.GENERAL, "evaluating the nested"   // LL changed call 7 April 2012
									+ "\nexpressions at the following positions:\n"
									+ work.printCallStack(), e);
				}
			}
		}
		if (done) {
			// clean up before exit:
			close(false);
			return;
		}

		// Create the central naming authority that is used by _all_ nodes
		String hostname = InetAddress.getLocalHost().getHostName();
		Registry rg = LocateRegistry.createRegistry(Port);
		rg.rebind("TLCServer", this);
		MP.printMessage(EC.TLC_DISTRIBUTED_SERVER_RUNNING, hostname);
		
		// First register TLCSERVER with RMI and only then wait for all FPSets
		// to become registered. This only waits if we use distributed
		// fingerprint set (FPSet) servers which have to partition the
		// distributed hash table (fingerprint space) prior to starting model
		// checking.
		waitForFPSetManager();
		
		// Add the init state(s) to the local FPSet or distributed servers 
		for (Long fp : initFPs) {
			fpSetManager.put(fp);
		}
		
		/*
		 * This marks the end of the master and FPSet server initialization.
		 * Model checking can start now.
		 */

		// Model checking results to be collected after model checking has finished
		long oldNumOfGenStates = 0;
        long oldFPSetSize = 0;
		
		// Wait for completion, but print out progress report and checkpoint
		// periodically.
    	synchronized (this) { //TODO convert to do/while to move initial wait into loop
    		wait(REPORT_INTERVAL);
    	}
		while (true) {
			if (TLCGlobals.doCheckPoint()) {
				// Periodically create a checkpoint assuming it is activated
				checkpoint();
			}
			synchronized (this) {
				if (!done) {
					final long numOfGenStates = getStatesComputed();
					final long fpSetSize = fpSetManager.size();
					
			        // print progress showing states per minute metric (spm)
			        final double factor = REPORT_INTERVAL / 60000d;
					statesPerMinute = (long) ((numOfGenStates - oldNumOfGenStates) / factor);
					distinctStatesPerMinute = (long) ((fpSetSize - oldFPSetSize) / factor);
			        
					// print to system.out
					MP.printMessage(EC.TLC_PROGRESS_STATS, new String[] { String.valueOf(trace.getLevelForReporting()),
			                String.valueOf(numOfGenStates), String.valueOf(fpSetSize),
			                String.valueOf(getNewStates()), String.valueOf(statesPerMinute), String.valueOf(distinctStatesPerMinute) });
					
					// Make the TLCServer main thread sleep for one report interval
					wait(REPORT_INTERVAL);
					
					// keep current values as old values
					oldFPSetSize = fpSetSize;
					oldNumOfGenStates = numOfGenStates;
				}
				if (done) {
					break;
				}
			}
		}
		
		/*
		 * From this point on forward, we expect model checking to be done. What
		 * is left open, is to collect results and clean up
		 */
		
		long workerOverallCacheRate = 0L;
		
		// Wait for all the server threads to die.
		for (final Entry<TLCServerThread, TLCWorkerRMI> entry : threadsToWorkers.entrySet()) {
			final TLCServerThread thread = entry.getKey();
			
			thread.join();
			
			// print worker stats
			int sentStates = thread.getSentStates();
			int receivedStates = thread.getReceivedStates();
			double cacheHitRatio = thread.getCacheRateRatio();
			URI name = thread.getUri();
			MP.printMessage(EC.TLC_DISTRIBUTED_WORKER_STATS,
					new String[] { name.toString(), Integer.toString(sentStates), Integer.toString(receivedStates),
					String.format("%1$,.3f", cacheHitRatio) });

			final TLCWorkerRMI worker = entry.getValue();
			try {
				workerOverallCacheRate = worker.getCacheRate();
				worker.exit();
			} catch (NoSuchObjectException e) {
				// worker might have been lost in the meantime
				MP.printMessage(EC.GENERAL, "Ignoring attempt to exit dead worker");
			}
		}
		
		// Only shutdown the thread pool if we exit gracefully
		es.shutdown();
		
		// Collect model checking results before exiting remote workers
		finalNumberOfDistinctStates = fpSetManager.size();
		final long statesGenerated = getStatesComputed(workerOverallCacheRate);
		final long statesLeftInQueue = getNewStates();
		
		final int level = trace.getLevelForReporting();
		
		statesPerMinute = 0;
		distinctStatesPerMinute = 0;

		// Postprocessing:
		if (hasNoErrors()) {
			// We get here because the checking has succeeded.
			final double actualProb = fpSetManager.checkFPs();
			final long statesSeen = fpSetManager.getStatesSeen();
			ModelChecker.reportSuccess(finalNumberOfDistinctStates, actualProb, statesSeen);
		} else if (keepCallStack) {
			// We redo the work on the error state, recording the call stack.
			work.setCallStack();
		}
		
		// Finally print the results
		printSummary(level, statesGenerated, statesLeftInQueue, finalNumberOfDistinctStates, hasNoErrors(), workerOverallCacheRate);
		MP.printMessage(EC.TLC_FINISHED);
		MP.flush();

		// Close trace and (distributed) _FPSet_ servers!
		close(hasNoErrors());
		
		// dispose RMI leftovers
		rg.unbind("TLCServer");
		UnicastRemoteObject.unexportObject(this, false);
	}
	
	/**
	 * Makes the flow of control wait for the IFPSetManager implementation to
	 * become fully initialized.<p>
	 * For the non-distributed FPSet implementation, this is true right away.
	 */
	protected void waitForFPSetManager() throws InterruptedException {
		// no-op
	}

	public long getStatesGeneratedPerMinute() {
		return statesPerMinute;
	}
	
	public long getDistinctStatesGeneratedPerMinute() {
		return distinctStatesPerMinute;
	}

	public long getAverageBlockCnt() {
		return blockSelector.getAverageBlockCnt();
	}
	
	/**
	 * @return true iff model checking has not found an error state
	 */
	private boolean hasNoErrors() {
		return errState == null;
	}

	/**
	 * @return
	 */
	public synchronized long getNewStates() {
		long res = stateQueue.size();
		for (TLCServerThread thread : threadsToWorkers.keySet()) {
			res += thread.getCurrentSize();
		}
		return res;
	}

	// use fingerprint server to determine how many states have been calculated
    public synchronized long getStatesComputed() throws RemoteException {
    	long statesSeen = 0L;
    	
		// Workers cache fingerprints locally to reduce network round-trips. This
		// makes the result of fpSetManager.getStatesSeen() miss the cache hits
		// on the worker side. Thus, query each worker for its cache hit rate
		// and add it to the overall states seen.
    	for (TLCWorkerRMI worker : threadsToWorkers.values()) {
			// worker is null when model checking is over, but we cling to the
			// refs to collect statistics.
    		statesSeen += worker.getCacheRate();
		}
    	
    	return getStatesComputed(statesSeen);
	}
    
    public synchronized long getStatesComputed(long overallCacheRate) throws RemoteException {
    	return fpSetManager.getStatesSeen() + overallCacheRate;
    }
    	
	// query each worker for how many states computed (workers might disconnect)
//    private long getStatesComputed() throws RemoteException {
//    	long res = 0L;
//		for (TLCWorkerRMI worker : threadsToWorkers.values()) {
//			res += worker.getStatesComputed();
//		}
//		return res;
//	}
    
    /**
     * This allows the toolbox to easily display the last set
     * of state space statistics by putting them in the same
     * form as all other progress statistics.
     * @param workerOverallCacheRate 
     */
    public static final void printSummary(int level, long statesGenerated, long statesLeftInQueue, long distinctStates, boolean success, long workerOverallCacheRate) throws IOException
    {
		if (TLCGlobals.tool) {
            MP.printMessage(EC.TLC_PROGRESS_STATS, new String[] { String.valueOf(level),
                    String.valueOf(statesGenerated), String.valueOf(distinctStates),
                    String.valueOf(statesLeftInQueue), "0", "0" });
        }

        MP.printMessage(EC.TLC_STATS, new String[] { String.valueOf(statesGenerated),
                String.valueOf(distinctStates), String.valueOf(statesLeftInQueue) });
        if (success) {
            MP.printMessage(EC.TLC_SEARCH_DEPTH, String.valueOf(level));
        }
    }

	public static void main(String argv[]) {
		MP.printMessage(EC.GENERAL, "TLC Server " + TLCGlobals.versionOfTLC);
		TLCStandardMBean tlcServerMXWrapper = TLCStandardMBean.getNullTLCStandardMBean();
		TLCServer server = null;
		try {
			TLCGlobals.setNumWorkers(0);
			final TLCApp app = TLCApp.create(argv);
			if (expectedFPSetCount > 0) {
				server = new DistributedFPSetTLCServer(app, expectedFPSetCount);
			} else {
				server = new TLCServer(app);
			}
			tlcServerMXWrapper = new TLCServerMXWrapper(server);
			if(server != null) {
				Runtime.getRuntime().addShutdownHook(new Thread(new WorkerShutdownHook(server)));
				server.modelCheck();
			}
		} catch (Throwable e) {
			System.gc();
			// Assert.printStack(e);
			if (e instanceof StackOverflowError) {
				MP.printError(EC.SYSTEM_STACK_OVERFLOW, e);
			} else if (e instanceof OutOfMemoryError) {
				MP.printError(EC.SYSTEM_OUT_OF_MEMORY, e);
			} else {
				MP.printError(EC.GENERAL, e);
			}
			if (server != null) {
				try {
					server.close(false);
				} catch (Exception e1) {
					MP.printError(EC.GENERAL, e1);
				}
			}
		} finally {
			tlcServerMXWrapper.unregister();
		}
	}

	/**
	 * @return Number of currently registered workers
	 */
	public int getWorkerCount() {
		return threadsToWorkers.size();
	}
	
	/**
	 * @return
	 */
	synchronized TLCServerThread[] getThreads() {
		return threadsToWorkers.keySet().toArray(new TLCServerThread[threadsToWorkers.size()]);
	}
	
	public boolean isRunning() {
		return !done;
	}
	
	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.TLCServerRMI#isDone()
	 */
	public boolean isDone() throws RemoteException {
		return done;
	}
	
	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.TLCServerRMI#getSpec()
	 */
	public String getSpecFileName() throws RemoteException {
		return this.work.getFileName();
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.TLCServerRMI#getConfig()
	 */
	public String getConfigFileName() throws RemoteException {
		return this.work.getConfigName();
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.TLCServerRMI#getFile(java.lang.String)
	 */
	public byte[] getFile(final String file) throws RemoteException {
		// sanitize file to only last part of the path
		// to make sure to not load files outside of spec dir
		String name = new File(file).getName();
		
		// Resolve all 
		File f = new SimpleFilenameToStream().resolve(name);
		return read(f);
	}
	
	private byte[] read(final File file) {
		if (file.isDirectory())
			throw new RuntimeException("Unsupported operation, file "
					+ file.getAbsolutePath() + " is a directory");
		if (file.length() > Integer.MAX_VALUE)
			throw new RuntimeException("Unsupported operation, file "
					+ file.getAbsolutePath() + " is too big");

		Throwable pending = null;
		FileInputStream in = null;
		final byte buffer[] = new byte[(int) file.length()];
		try {
			in = new FileInputStream(file);
			in.read(buffer);
		} catch (Exception e) {
			pending = new RuntimeException("Exception occured on reading file "
					+ file.getAbsolutePath(), e);
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (Exception e) {
					if (pending == null) {
						pending = new RuntimeException(
								"Exception occured on closing file"
										+ file.getAbsolutePath(), e);
					}
				}
			}
			if (pending != null) {
				throw new RuntimeException(pending);
			}
		}
		return buffer;
	}
	

	/**
	 * Tries to exit all connected workers
	 */
	private static class WorkerShutdownHook implements Runnable {
		
		private final TLCServer server;
		
		public WorkerShutdownHook(final TLCServer aServer) {
			server = aServer;
		}

		/* (non-Javadoc)
		 * @see java.lang.Runnable#run()
		 */
		public void run() {
			for (TLCWorkerRMI worker : server.threadsToWorkers.values()) {
				try {
					worker.exit();
				} catch (java.rmi.ConnectException e)  {
					// happens if worker has exited already
				} catch (java.rmi.NoSuchObjectException e) {
					// happens if worker has exited already
				} catch (IOException e) {
					//TODO handle more gracefully
					MP.printError(EC.GENERAL, e);
				}
			}
		}
	}
}
