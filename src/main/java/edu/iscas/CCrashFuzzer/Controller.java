package edu.iscas.CCrashFuzzer;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.iscas.CCrashFuzzer.AflCli.AflCommand;
import edu.iscas.CCrashFuzzer.AflCli.AflException;
import edu.iscas.CCrashFuzzer.Conf.MaxDownNodes;
import edu.iscas.CCrashFuzzer.FaultSequence.FaultPoint;
import edu.iscas.CCrashFuzzer.FaultSequence.FaultStat;
import edu.iscas.CCrashFuzzer.utils.FileUtil;
//We do not trigger remote crash in this controller.
//This controller aims to trigger local crashes for systems deployed as processes in the same machine
public class Controller {
	public Cluster cluster;
	public boolean running;
	public Set<ClientHandler> clients;
	public int CONTROLLER_PORT = 8888;
	public FaultSequence faultSequence; //store current crash point ID to the crash before point
    public Thread serverThread;
    public ServerSocket serverSocket;
    public boolean faultInjected;
    public boolean injectionAborted;//cannot schedule current fault sequence any more
    public ArrayList<String> rst;
    public Conf favconfig;
    List<MaxDownNodes> currentCluster = new ArrayList<MaxDownNodes>();
    public final int maxClients = 300;

    public Controller(Cluster cluster, int port, Conf favconfig) {
    	this.cluster = cluster;
    	this.running = false;
    	this.CONTROLLER_PORT = port;
    	this.favconfig = favconfig;
    	this.faultInjected = false;
    	this.injectionAborted = false;
    	this.rst = new ArrayList<String>();
    	this.clients = Collections.synchronizedSet(new HashSet<ClientHandler>());
    	currentCluster = Mutation.cloneCluster(favconfig.maxDownGroup);
    }

    public void startController() {
		running = true;
		serverThread = new Thread() {

			@Override
			public void run() {
				// TODO Auto-generated method stub
				try{
				    Stat.log("Controller port:"+CONTROLLER_PORT);
					serverSocket = new ServerSocket(CONTROLLER_PORT);
				    Stat.log("Controller started ...");
		            int counter = 0;
		            while(running){
		            	while(clients.size()>maxClients) {
		            		Thread.currentThread().sleep(500);
		            	}
		            	counter++;
		            	Socket socket = serverSocket.accept();  //server accept the client connection request
		            	//System.out.println("a client "+counter+" was connected"+socket.getRemoteSocketAddress());
		            	ClientHandler sct = new ClientHandler(socket,counter); //send  the request to a separate thread
		            	sct.start();
		            	clients.add(sct);
		            }
		            serverSocket.close();
		         } catch(Exception e) {
		            System.out.println(e);
		         }
			}
		};
		serverThread.start();
	}

	public void stopController() {
		running = false;
		try {
			if(serverThread.isAlive()) {
				serverThread.interrupt();
			}
			for(ClientHandler t:clients) {
				if(t.isAlive()) {
					t.interrupt();
				}
			}
			if(serverSocket != null && !serverSocket.isClosed()) {
				serverSocket.close();
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			System.err.println("exception when stopping controller ...");
			e.printStackTrace();
		}
		File file = favconfig.CUR_CRASH_FILE;
		if(file.exists()) {
			System.out.println("Detete cur crash file.");
			file.delete();
		}
		System.out.println("Controller was stopped.");
	}

	public void prepareFaultSeq(FaultSequence p) {
		if(p == null || p.isEmpty()) {
			this.faultInjected = true;
			Stat.log("No faults to inject in this round.");
		}
		faultSequence = p;
		//ljy--这里的reset不是清空整个faultSequence，而是相当于重置faultSequence的部分测试状态。
		faultSequence.reset();
		updataCurCrashPointFile();
		Stat.log("Current fault sequence was prepared.");
	}

	public void updataCurCrashPointFile() {
		if(faultSequence == null || faultSequence.isEmpty()) {
			//ljy--这里应该是首次还没建立起faultSequence的时候，faultSequence是空的
			File file = favconfig.CUR_CRASH_FILE;
			if(file.exists()) {
			    file.delete();
				if(favconfig.UPDATE_CRASH != null) {
		            String path = favconfig.UPDATE_CRASH.getAbsolutePath();
		            String workingDir = path.substring(0, path.lastIndexOf("/"));
		            RunCommand.run(path, workingDir);
		        }
			}
		} else {
			//ljy--如果存在该文件（该文件实际上就是zk363curCrash--针对zookeeper而言的）
			FileUtil.genereteFaultSequenceFile(faultSequence, favconfig.CUR_CRASH_FILE);
			
			if(favconfig.UPDATE_CRASH != null) {
                String path = favconfig.UPDATE_CRASH.getAbsolutePath();
                String workingDir = path.substring(0, path.lastIndexOf("/"));
                RunCommand.run(path, workingDir);
            }
		}
	}

	public class ClientHandler extends Thread {
	    final Socket socket;
		final int id;
		public ClientHandler(Socket socket,  int id) {
			this.socket = socket;
			this.id = id;
		}

		public void shutdown() {
			if(socket != null && !socket.isClosed()) {
				try {
					socket.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		@Override
		public void run() {
			boolean injectFault = false;
			int pendingFault = 0;
			FaultPoint pendingPoint = null;
			try{
				//System.out.println("ClientHandler "+id+" was started!"+socket.getLocalPort()+":"+socket.getRemoteSocketAddress());
				DataInputStream inStream = new DataInputStream(socket.getInputStream());
				DataOutputStream outStream = new DataOutputStream(socket.getOutputStream());
				ObjectInputStream objIn = new ObjectInputStream(inStream);
				int ioID = inStream.readInt();
				String reportNodeIp = inStream.readUTF();
				String cliID = inStream.readUTF()+" for ioID "+ioID+", ";
				//System.out.println("ClientHandler-" +id+ ": msg is :"+mess);

				//ljy--这里开始就是线程对faultSequence的互斥访问了，
				//ljy--因为上面startController的线程中会循环创建新的ClientHandler对象线程
				//ljy--这里下面的操作应该只有一个线程会完成对所有faultSequence与IOPoint序列重合的操作
				synchronized(faultSequence) {
					int curFault = faultSequence.curFault.get();
					int curAppear = 0;
					if(curFault == -1 || curFault >= faultSequence.seq.size()) {
						curAppear = 0;
					} else {
						curAppear = faultSequence.seq.get(curFault).curAppear;
					}
					//System.out.println("Client "+id+" enter synchronized area: "+socket.getRemoteSocketAddress());

					//ljy--整体上这个if语句是为了在注入之前先判断faultSequence合不合法
					if(!faultSequence.isEmpty()) {
						if(faultInjected || injectionAborted) {//all the faults have been injected or aborted
							//all faults have been tested
//							Stat.log(cliID+"-----------faultInjected || injectionAborted----------");
	                        outStream.writeUTF("CONTI");
	                        outStream.writeInt(curFault);
							outStream.writeInt(curAppear);
//							//System.out.println("Send continue response to client "+id+":"+socket.getRemoteSocketAddress());
							outStream.flush();
							inStream.close();
							outStream.close();
							socket.close();
							//ljy--所有faultPoint都已经注入或者测试过了，这个socket可以关闭掉了

						} else {//check faultSequence
							//ljy--对每个FaultPoint都找到需要在IOPoint序列中插入的位置
							for(int i = curFault;i<faultSequence.seq.size(); i++) {
								FaultPoint p = faultSequence.seq.get(i);
								//ljy--这里是为了找故障点插到哪个IOPoint下进行执行，确定AppearIdx
								if(p.ioPt.ioID == ioID && p.curAppear < p.ioPt.appearIdx) {
									//meet the a fault point, check appear indexes
									p.curAppear++;
//									Stat.log(cliID+"---------"+i+"th--"+ioID+"'s curAppear++:"+p.curAppear+"----------");
									if(p.curAppear == p.ioPt.appearIdx) {
										//can inject a fault
										Stat.log(cliID+"---------"+i+"th--"+ioID+" meet:"+p.curAppear+"----------");
										pendingFault = i;
										injectFault = true; //这个布尔值应该是说：已经把FaultPoint插到对应的IOPoint执行的位置了。
										pendingPoint = p;
									}
								}
							}
							//ljy--如果没有任何一个FaultPoint被插入到IOPoint序列中，那injectFault就会是false
							if(!injectFault) {//not the time to inject the fault or do not match a fault
//								Stat.log(cliID+"---------not the time to inject the fault or do not match a fault----------");
								outStream.writeUTF("CONTI");
		                        outStream.writeInt(curFault);
								outStream.writeInt(curAppear);
								//System.out.println("Send continue response to client "+id+":"+socket.getRemoteSocketAddress());
								outStream.flush();
								inStream.close();
								outStream.close();
								socket.close();
							}
						}
					} else {//no fault to inject
//					    rst.add(Stat.log("Controller already has an accepted node id is "+acceptedCrashNode));
//						Stat.log(cliID+"---------no fault to inject----------");
						
                        outStream.writeUTF("CONTI");
                        outStream.writeInt(curFault);
						outStream.writeInt(curAppear);
						//System.out.println("Send continue response to client "+id+":"+socket.getRemoteSocketAddress());
						outStream.flush();
						inStream.close();
						outStream.close();
						socket.close();
					}
				}

				//ljy--故障点已经被插入到IOPoint序列中的对应的位置了。
				if(injectFault) {//inject a fault
					//ljy--说实话下面这一段我好像没看懂

					int cur_Fault = faultSequence.curFault.get();
					while(pendingFault != cur_Fault) {
						//wait for the fault time
						//ljy--我好奇的是这里为什么要等500ms
						//ljy--这里是当pendingFault 待定的FaultPoint和 current Fault索引进行对比。
						//ljy--就把cpu让给其他线程执行，为什么呢？
						try {
							Thread.sleep(500);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						cur_Fault = faultSequence.curFault.get();
					}
//					Stat.log(cliID+"---------Time to inject fault "+cur_Fault+"----------");

					//ljy---如果等待执行的FaultPoint的类型是Crash节点
					if(pendingPoint.stat.equals(FaultStat.CRASH)) {
						pendingPoint.actualNodeIp = reportNodeIp;
						for(int i = cur_Fault; i< faultSequence.seq.size(); i++) {
							if(faultSequence.seq.get(i).stat.equals(FaultStat.REBOOT)
									&& faultSequence.seq.get(i).tarNodeIp.equals(pendingPoint.tarNodeIp)) {
								faultSequence.seq.get(i).actualNodeIp = reportNodeIp;
								//ljy--这里判断了当前等待执行的FaultPoint类型是Crash后，为什么又要放个循环来看后面有没有reboot的呢？
								//ljy--我们知道，因为实际上执行过程中，实际被崩溃的节点不一定是之前tarNodeIp对应的节点。
								//ljy--有可能是别的节点，所以用actualNodeIp来存放实际被crash的节点。
								//ljy--那假如后面有对同一个tarNodeIp进行reboot的操作的话，
								//ljy--那是不是那个reboot操作的faultPoint的actualNodeIp也需要被写入实际被crash的节点。
								//ljy--当然，这里只需要改掉遇到的第一个对应crash的reboot的操作就行，下一次的crash操作指不定是crash到哪个节点呢。
								break;
							}
						}
						
						rst.add(Stat.log("Meet "+cur_Fault+"th fault point [CRASH]:"+pendingPoint));
						//ljy--检查是不是crash到了一个还没活过来的节点
						if(Mutation.isDeadNode(currentCluster, pendingPoint.actualNodeIp)) {
							throw new AbortFaultException("Crashing a dead node "+pendingPoint.actualNodeIp+"!!!");
						}

						Stat.log(cliID+"---------For fault "+cur_Fault+", time to info reporting node: CRASH:"+faultSequence.seq.get(cur_Fault).curAppear+"----------");
						outStream.writeUTF("CRASH");
						outStream.writeInt(cur_Fault);
						outStream.writeInt(faultSequence.seq.get(cur_Fault).curAppear);
//						//System.out.println("Send continue response to client "+id+":"+socket.getRemoteSocketAddress());
						outStream.flush();
						inStream.close();
						outStream.close();
						socket.close();
//						Stat.log(cliID+"---------For fault "+cur_Fault+", informed reporting node: CRASH:"+faultSequence.seq.get(cur_Fault).curAppear+"----------");
						
						
						String[] args = new String[3];
						args[0] = pendingPoint.actualNodeIp;
						args[1] = String.valueOf(favconfig.AFL_PORT);
						args[2] = AflCommand.SAVE.toString();

						//ljy--这里执行了一下AflCLi类的main方法，执行这个干吗？
						AflCli.main(args);
						
						//Restart the node
						//ljy--这里是杀死节点的操作，在ClientHandler的socket关闭后。
		        		rst.add(Stat.log("Prepare to crash node "+pendingPoint.actualNodeIp));
		                List<String> crashRst = cluster.killNode(pendingPoint.actualNodeIp, pendingPoint.actualNodeIp);
		                rst.addAll(crashRst);
		                //CrashTriggerMain.generateFailureInfo(restartRst, point, acceptedCrashNode, CUR_CRASH_NODE_NAME, restarted, "restart-failure");
		                rst.add(Stat.log("node "+pendingPoint.actualNodeIp+" was killed!"));

						//ljy--在杀死节点后，需要更新一下当前测试状态中，整个集群中节点的存活情况
		                Mutation.buildClusterStatus(currentCluster, pendingPoint.actualNodeIp, FaultStat.CRASH);

					//ljy--如果当前等待执行的故障点是Reboot
					} else if(pendingPoint.stat.equals(FaultStat.REBOOT)) {
						rst.add(Stat.log("Meet "+cur_Fault+"th fault point[REBOOT]:"+pendingPoint));
						if(Mutation.isAliveNode(currentCluster, pendingPoint.actualNodeIp)) {
							throw new AbortFaultException("Restarting an alive node "+pendingPoint.actualNodeIp+"!!!");
						}
						
						//Restart the node
		        		rst.add(Stat.log("Prepare to restart node "+pendingPoint.actualNodeIp+" before continue on node "+reportNodeIp));
		                List<String> restartRst = cluster.restartNode(pendingPoint.actualNodeIp);
		                rst.addAll(restartRst);
		                //CrashTriggerMain.generateFailureInfo(restartRst, point, acceptedCrashNode, CUR_CRASH_NODE_NAME, restarted, "restart-failure");
		                rst.add(Stat.log("node "+pendingPoint.actualNodeIp+" was restarted!"));
			            
		                Stat.log(cliID+"---------For fault "+cur_Fault+", time to info reporting node: REBOOT:"+faultSequence.seq.get(cur_Fault).curAppear+"----------");
						
		                outStream.writeUTF("REBOOT");
						outStream.writeInt(cur_Fault);
						outStream.writeInt(faultSequence.seq.get(cur_Fault).curAppear);
//						//System.out.println("Send continue response to client "+id+":"+socket.getRemoteSocketAddress());
						outStream.flush();
						inStream.close();
						outStream.close();
						socket.close();
//						Stat.log(cliID+"---------For fault "+cur_Fault+", informed reporting node: REBOOT:"+faultSequence.seq.get(cur_Fault).curAppear+"----------");

						//ljy--在重启节点后，需要更新一下当前测试状态中，整个集群中节点的存活情况
		                Mutation.buildClusterStatus(currentCluster, pendingPoint.actualNodeIp, FaultStat.REBOOT);
					} else {
						//no need to inject faults
						Stat.log(cliID+"---------For fault "+cur_Fault+", time to info reporting node: NONE:"+faultSequence.seq.get(cur_Fault).curAppear+"----------");
						
                        outStream.writeUTF("CONTI");
						outStream.writeInt(cur_Fault);
						outStream.writeInt(faultSequence.seq.get(cur_Fault).curAppear);
//						//System.out.println("Send continue response to client "+id+":"+socket.getRemoteSocketAddress());
						outStream.flush();
						inStream.close();
						outStream.close();
						socket.close();
//						Stat.log(cliID+"---------For fault "+cur_Fault+", informed reporting node: REBOOT:"+faultSequence.seq.get(cur_Fault).curAppear+"----------");
						
					}
					int injectedFaultsNum = faultSequence.curFault.incrementAndGet();
					if(injectedFaultsNum >= faultSequence.seq.size()) {
						faultInjected = true;
					}
					injectFault = false;
				}
		    } catch (IOException | AbortFaultException | AflException e) {
		    	if(injectFault) {//cannot schedule this fault sequence
		    		injectionAborted = true;
		    		System.err.println(e.getMessage());
		    		System.err.println(e);
		    	}
		    } finally {
		    	if(socket != null && !socket.isClosed()) {
		    		try {
						socket.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
		    	}
				clients.remove(this);
				//System.out.println("ClientHandler-" + id + " exit!! ");
		    }
		}
	}

    public int getRandom(int start,int end) {
    	int num = (int) (Math.random()*(end-start+1)+start);
		return num;
	}
    
    public static class AbortFaultException extends Exception {
		public AbortFaultException(String errorMessage) {
	        super(errorMessage);
	    }
	}
}
