package edu.iscas.CCrashFuzzer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import edu.iscas.CCrashFuzzer.Conf.MaxDownNodes;
import edu.iscas.CCrashFuzzer.FaultSequence.FaultPoint;
import edu.iscas.CCrashFuzzer.FaultSequence.FaultPos;
import edu.iscas.CCrashFuzzer.FaultSequence.FaultStat;

public class Mutation {
	public static void buildClusterStatus(List<MaxDownNodes> currentCluster, String faultNodeIp, FaultStat faultType) {
		for(MaxDownNodes subCluster:currentCluster) {
//			System.out.println("mutation, maxDown "+subCluster.maxDown
//					+", alive:"+subCluster.aliveGroup+", dead:"+subCluster.deadGroup);
			if(subCluster.aliveGroup.contains(faultNodeIp) && faultType.equals(FaultStat.CRASH)) {
				subCluster.maxDown--;
				subCluster.aliveGroup.remove(faultNodeIp);
				subCluster.deadGroup.add(faultNodeIp);

//				System.out.println("mutation, move "+faultNodeIp+" from alive to dead."+subCluster.maxDown);
				break;
			} else if(subCluster.deadGroup.contains(faultNodeIp) && faultType.equals(FaultStat.REBOOT)) {
				subCluster.maxDown++;
				subCluster.deadGroup.remove(faultNodeIp);
				subCluster.aliveGroup.add(faultNodeIp);
//				System.out.println("mutation, move "+faultNodeIp+" from dead to alive."+subCluster.maxDown);
				break;
			} else {
				continue;
			}
		}
	}
	public static boolean isAliveNode(List<MaxDownNodes> currentCluster, String faultNodeIp) {
		for(MaxDownNodes subCluster:currentCluster) {
//			System.out.println("mutation, maxDown "+subCluster.maxDown
//					+", alive:"+subCluster.aliveGroup+", dead:"+subCluster.deadGroup);
			if(subCluster.aliveGroup.contains(faultNodeIp)) {
				return true;
			} else {
				continue;
			}
		}
		return false;
	}
	public static boolean isDeadNode(List<MaxDownNodes> currentCluster, String faultNodeIp) {
		for(MaxDownNodes subCluster:currentCluster) {
//			System.out.println("mutation, maxDown "+subCluster.maxDown
//					+", alive:"+subCluster.aliveGroup+", dead:"+subCluster.deadGroup);
			if(subCluster.deadGroup.contains(faultNodeIp)) {
				return true;
			} else {
				continue;
			}
		}
		return false;
	}
	public static List<MaxDownNodes> cloneCluster(List<MaxDownNodes> srcCluster) {
		List<MaxDownNodes> desCluster = new ArrayList<MaxDownNodes>();
		for(MaxDownNodes sub:srcCluster) {
    		MaxDownNodes group = new MaxDownNodes();
    		group.maxDown = sub.maxDown;
    		group.aliveGroup = new HashSet<String>();
    		group.aliveGroup.addAll(sub.aliveGroup);
    		
    		group.deadGroup = new HashSet<String>();
    		group.deadGroup.addAll(sub.deadGroup);
    		desCluster.add(group);
    	}
		return desCluster;
	}

	//ljy--在变异序列的过程，添加对同源序列的添加。
	public static void mutateFaultSequence(QueueEntry q, Conf conf) {
		List<QueueEntry> mutates = new ArrayList<QueueEntry>();   //创建了一个新的变异队列，用来存放q的本次变异的所有故障序列
		FaultSequence original_faults = q.faultSeq;
		int io_index = q.candidate_io; 			//candidateio，这个才是最后一个FaultPoint的后一个IOPoint的索引
		int fault_index = q.max_match_fault;    //这里的max-match-fault是当前原故障序列q中，最大一共匹配到多少FaultPoint的数量

		
		if(io_index == q.ioSeq.size() || fault_index < original_faults.seq.size() || original_faults.seq.size() >= conf.MAX_FAULTS) {
			//no I/O points to inject a new fault
			//or current I/O points do not match with the fault sequence
			q.mutates = mutates;
			q.favored_mutates = q.mutates;
			//ljy--这里不用收集同源变异序列，因为这里的mutates是空的列表，不需要
			return;
		}

		List<MaxDownNodes> currentCluster = Mutation.cloneCluster(conf.maxDownGroup);   //复制上一次集群的所有状态
		for(FaultPoint fault:original_faults.seq) {
			buildClusterStatus(currentCluster, fault.tarNodeIp, fault.stat);
		}
		
		int lastIO = q.ioSeq.size();
		Stat.log("Start to check fault point from "+io_index+" th I/O point for "+q.ioSeq.size()+" I/O points.");
		
		for(int curIO = io_index; curIO< lastIO; curIO++) {     //ljy--从当前原故障序列q中的最后一个FaultPoint以后开始，可进行注入。
			/*
			List<IOPoint> beforeNeighbors = new ArrayList<IOPoint>();
			List<IOPoint> afterNeighbors = new ArrayList<IOPoint>();
			int adjacentNewCovs = 0;
			for(int i = io_index-1; i >=0; i--) {
				if((q.ioSeq.get(io_index).TIMESTAMP - q.ioSeq.get(i).TIMESTAMP) <= conf.similarBehaviorWindow) {
					beforeNeighbors.add(q.ioSeq.get(i));
					adjacentNewCovs += q.ioSeq.get(i).newCovs;
				} else {
					break;
				}
			}
			for(int i = io_index; i < lastIO; i++) {
				if((q.ioSeq.get(i).TIMESTAMP - q.ioSeq.get(io_index).TIMESTAMP) <= conf.similarBehaviorWindow) {
					afterNeighbors.add(q.ioSeq.get(i));
					adjacentNewCovs += q.ioSeq.get(i).newCovs;
				} else {
					break;
				}
			}
			*/
			for(MaxDownNodes subCluster:currentCluster) {
				if(subCluster.aliveGroup.contains(q.ioSeq.get(curIO).ip)   //ljy--如果存活组和宕机组中存在这个io操作对应的ip的话，就可继续进行注入（这个真的有必要吗，节点不是本来就是非死即活）
						|| subCluster.deadGroup.contains(q.ioSeq.get(curIO).ip)) {
					boolean canCrash = subCluster.aliveGroup.contains(q.ioSeq.get(curIO).ip) && (subCluster.maxDown-1)>=0; //ljy--好吧，上面这个是为这里两个布尔变量的判断服务的。
					boolean canReboot = subCluster.deadGroup.size()>0 && !subCluster.deadGroup.contains(q.ioSeq.get(curIO).ip);
					if(canCrash) {
						FaultSequence faults = new FaultSequence();    //创建了新的故障序列。
						faults.seq = new ArrayList<FaultPoint>();
						faults.seq.addAll(original_faults.seq);        //ljy--先把之前的所有FP继承过来，然后在这个基础上再加新的一个FP。
						FaultPoint p  = new FaultPoint();
						p.ioPt = q.ioSeq.get(curIO);
						p.ioPtIdx = curIO;
						p.pos = FaultPos.BEFORE;
						p.tarNodeIp = p.ioPt.ip;                       //ljy--crash的节点，都是当前IOPoint对应的节点
						p.stat = FaultStat.CRASH;
						p.actualNodeIp = null;                         //ljy--这个是在运行时回馈的，即假如，虽然这里选择了C1ZK1节点进行崩溃，但是实际进行故障注入后，崩溃的可能不是C1ZK1节点，而是另一个节点。
						faults.seq.add(p);
						if(q.recovery_io_id.contains(p.ioPt.ioID)) {
							faults.on_recovery = true;
						}
						faults.reset();
//						faults.adjacent_new_covs = adjacentNewCovs;
						
						QueueEntry new_q = new QueueEntry();
						//ljy--创建了新的队列对象，用于将上面生成的故障序列作为新的一个队列对象，因为mutates是一个QueueEntry列表
						//ljy--且FaultSequence仅仅包含的是FaultPoint，而不是所有的IOPoint，需要一个队列对象来承载所有的IO点以及对应位置上注入的故障点
						new_q.ioSeq = q.ioSeq;
						new_q.faultSeq = faults;
						new_q.favored = true;
						new_q.exec_s  = q.exec_s;
						new_q.bitmap_size = q.bitmap_size;
						new_q.handicap = 0;
						
						if(q.not_tested_fault_id == null) {
							q.not_tested_fault_id = new HashSet<Integer>();
						}
						if(q.on_recovery_mutates == null) {
							q.on_recovery_mutates = new ArrayList<QueueEntry>();
						}
						int faultid = (p.ioPt.CALLSTACK+p.stat.toString()+p.tarNodeIp).hashCode();
						q.not_tested_fault_id.add(faultid);
						if(faults.on_recovery) {
							q.on_recovery_mutates.add(new_q);
						}
						
						mutates.add(new_q);

					}
					if(canReboot) {
						for(String rebootNode:subCluster.deadGroup) {   //ljy---对任意一个宕机组中的节点都可进行reboot操作
							FaultSequence faults = new FaultSequence();
							faults.seq = new ArrayList<FaultPoint>();
							faults.seq.addAll(original_faults.seq);
							FaultPoint p  = new FaultPoint();
							p.ioPt = q.ioSeq.get(curIO);
							p.ioPtIdx = curIO;
							p.pos = FaultPos.BEFORE;
							p.tarNodeIp = rebootNode;
							p.stat = FaultStat.REBOOT;
							p.actualNodeIp = null;
							faults.seq.add(p);
							if(q.recovery_io_id.contains(p.ioPt.ioID)) {
								faults.on_recovery = true;
							}
							faults.reset();
//							faults.adjacent_new_covs = adjacentNewCovs;
							
							QueueEntry new_q = new QueueEntry();
							new_q.ioSeq = q.ioSeq;
							new_q.faultSeq = faults;
							new_q.favored = true;
							new_q.exec_s  = q.exec_s;
							new_q.bitmap_size = q.bitmap_size;
							new_q.handicap = 0;

							if(q.not_tested_fault_id == null) {
								q.not_tested_fault_id = new HashSet<Integer>();
							}
							if(q.on_recovery_mutates == null) {
								q.on_recovery_mutates = new ArrayList<QueueEntry>();
							}
							int faultid = (p.ioPt.CALLSTACK+p.stat.toString()+p.tarNodeIp).hashCode();
							q.not_tested_fault_id.add(faultid);
							if(faults.on_recovery) {
								q.on_recovery_mutates.add(new_q);
							}
							
							mutates.add(new_q);
						}
					}
				}
			}
		}
		Stat.log("Got "+mutates.size()+" mutations.");

		q.mutates = mutates;
		q.favored_mutates = q.mutates;
		//ljy--在这里进行收集
		HomoSeqSetManager.collectHomoSeqSet(q);
	}
	
	public static List<QueueEntry> mutateFaultSequence_backup(QueueEntry q) {
		List<QueueEntry> mutates = new ArrayList<QueueEntry>();
//		int random = getRandomNumber(q.ioSeq.size());
		for(IOPoint pickedPt:q.ioSeq) {
			FaultSequence seq = new FaultSequence();
			seq.seq = new ArrayList<FaultPoint>();
			FaultPoint p  = new FaultPoint();
			p.ioPt = pickedPt;
			p.pos = FaultPos.BEFORE;
			p.tarNodeIp = p.ioPt.ip;
			p.stat = FaultStat.CRASH;
			seq.seq.add(p);
//			mutates.add(seq);
			QueueEntry new_q = new QueueEntry();
			new_q.ioSeq = q.ioSeq;
			new_q.faultSeq = seq;
			new_q.favored = true;
			mutates.add(new_q);
			// Stat.log("Return mutates:"+mutates);
		}
		return mutates;
	}
	public static List<QueueEntry> mutateTwoSimilarSeq(QueueEntry q){
		List<QueueEntry> mutates = new ArrayList<QueueEntry>();
//		int random = getRandomNumber(q.ioSeq.size());
		for(int i = 0; i<q.ioSeq.size()-1; i++) {
			if(q.ioSeq.get(i).CALLSTACK.toString().equals(q.ioSeq.get(i+1).CALLSTACK.toString())) {
				FaultSequence seq = new FaultSequence();
				seq.seq = new ArrayList<FaultPoint>();
				FaultPoint p  = new FaultPoint();
				p.ioPt = q.ioSeq.get(i);
				p.pos = FaultPos.BEFORE;
				p.tarNodeIp = p.ioPt.ip;
				p.stat = FaultStat.CRASH;
				seq.seq.add(p);

				QueueEntry new_q1 = new QueueEntry();
				new_q1.ioSeq = q.ioSeq;
				new_q1.faultSeq = seq;
				new_q1.favored = true;
				mutates.add(new_q1);
				
				FaultSequence seq2 = new FaultSequence();
				seq2.seq = new ArrayList<FaultPoint>();
				FaultPoint p2  = new FaultPoint();
				p2.ioPt = q.ioSeq.get(i+1);
				p2.pos = FaultPos.BEFORE;
				p2.tarNodeIp = p2.ioPt.ip;
				p2.stat = FaultStat.CRASH;
				seq2.seq.add(p2);
				
				QueueEntry new_q2 = new QueueEntry();
				new_q2.ioSeq = q.ioSeq;
				new_q2.faultSeq = seq2;
				new_q2.favored = true;
				mutates.add(new_q2);
				break;
			}
		}

		 Stat.log("Return mutates:"+mutates.size());
		return mutates;
	}
	//from 0 to limit-1
	public static int getRandomNumber(int limit) {
			int num = (int) (Math.random()*limit);
			return num;
		}
}
