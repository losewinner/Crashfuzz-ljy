package edu.iscas.CCrashFuzzer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class HomoSeqSetManager {
    public static List<HomologousSeqSet> homoSetQueue = new ArrayList<HomologousSeqSet>();
    public static Map<Integer, HomologousSeqSet> homoSetMap = new HashMap<Integer, HomologousSeqSet>();
    public static int homo_total_bugs;
    public static int homo_total_tested_cases;

    //ljy--这个函数属于是在测试前调用，得到的队列，都是没有被测试过的，所以下面还需要写个更新函数。
    //ljy--判断故障序列是属于哪个同源集合的，并将其加入到对应的同源集合中
    //ljy--这里传进来的参数是源QueueEntry故障序列，需要查找的是其的包含的mutates是属于哪些同源集合的。
    public static void collectHomoSeqSet(QueueEntry entry){
        //ljy--设定规则：初始化的时候，存在没有任何故障点的序列q，没有故障点的序列，无法被作为源头
        if(entry.faultSeq.seq == null||entry.faultSeq.seq.isEmpty()){
            return;
        }
        //ljy--对于传进来的entry，判断变异深度:
        //1. mutate depth = 0: 说明在原基础上没有被变异过，创建新的同源序列集合
        //2. mutate depth = 1：说明变异过了一次，其下的变异序列结果能被加入对应的同源序列集合中
        //3. mutate depth = 2：说明已经变异两次了，这个entry的变异结果，不能再被采用在同源序列集合中了。
        if(entry.mutate_depth == 0){
            HomologousSeqSet homoSet = new HomologousSeqSet();
            //ljy--设置源头id,使用faultSeq的唯一ID做标识
            homoSet.original_id = entry.faultSeq.getFaultSeqID();
            //ljy--添加源头序列也进homoSet，不然bugs的数量不对。
            entry.original_id = homoSet.original_id;
            homoSet.homoSeqSet.add(entry);
            for(QueueEntry mutate:entry.mutates){
                mutate.mutate_depth = entry.mutate_depth+1; //ljy--加深变异深度
                //ljy--将所有变异序列都加入同源序列集合（这是第一次变异，所以放心加入新的同源序列集合
                mutate.original_id = homoSet.original_id;
                homoSet.homoSeqSet.add(mutate);
            }

            homoSetQueue.add(homoSet);
            homoSetMap.computeIfAbsent(homoSet.original_id, k->homoSet);

        }else if(entry.mutate_depth == 1){
            //ljy--因为这里是已经变异第二次了，需要去找，对应的上一层entry在哪个HomoSeqSet中
            HomologousSeqSet homoSet = homoSetMap.computeIfPresent(entry.original_id,(k,v)->v);
            //ljy--通过homoSet找，其处在homoSetQueue中的哪里
            int index = homoSetQueue.indexOf(homoSet);
            if(index == -1){
                return;
            }
            for(QueueEntry mutate:entry.mutates){
                mutate.mutate_depth = entry.mutate_depth+1;
                mutate.original_id = homoSetQueue.get(index).original_id;
                homoSet.homoSeqSet.add(mutate);
            }
            homoSetQueue.set(index,homoSet);
            homoSetMap.computeIfPresent(homoSetQueue.get(index).original_id,(k,v)->homoSet);
        }else if(entry.mutate_depth == 2){
            //设置下一层的变异的变异深度为0，一个轮回结束
            for(QueueEntry mutate: entry.mutates){
                mutate.mutate_depth = 0;
            }
        }

    }

    //ljy--这里需要写一个更新函数，每一轮测试结束，被测的QueueEntry状态一定会发生改变，所以需要更新同源序列集合队列中的同源序列集合中的某个对应的QueueEntry
    public static void updateHomoSeqSetEntry(QueueEntry entry){
        //ljy--需要进行如下操作：
        //找到对应entry属于HomoSeqQueue中的哪个HomoSet
        //更新HomoSet中对应的QueueEntry
            //1. 直接将对应的QueueEntry覆盖写入
            //2. ljy之前设置的参数需要保持不变（即，mutatedepth和original_id这两个）
        int tmp_original_id = entry.original_id;
        HomologousSeqSet homoSet = homoSetMap.computeIfPresent(tmp_original_id, (k,v)->v);
        int index = homoSetQueue.indexOf(homoSet);
        if(index == -1){
            return;
        }
        if(homoSet == null){
            return;
        }
        homoSet.updateQueueEntry(entry);
        homoSetQueue.set(index,homoSet);
        //ljy--还要更新对应的Map中的
        homoSetMap.computeIfPresent(tmp_original_id,(k,v)->homoSet);
    }

    //ljy--统计测试过的总数以及bug数量是否与前面原版的相符合
    public static String compareTestedSeqsNum(int total_bugs, int tested_cases){

        homo_total_bugs = homoSetQueue.stream()
                .mapToInt(HomologousSeqSet::getTotal_trigger_bug_num) // 提取属性值并转为 IntStream
                .sum();

        homo_total_tested_cases = homoSetQueue.stream()
                .mapToInt(HomologousSeqSet::getTotal_already_tested_num)
                .sum();
        String rst = "";
        rst += "Homo Total bugs: "+homo_total_bugs+"\n";
        rst += "Homo Total tested cases: "+homo_total_tested_cases+"\n";
        if(total_bugs == homo_total_bugs && homo_total_tested_cases == tested_cases){
            rst += "Homo Test is true\n";
        }
        rst += "Homo Test has someplace mistake\n";
        return rst;
    }
    //ljy--全局统计函数，统计之前想要看到的各种指标,仿写FuzzInfo的generateClientReport
    public static String generateHomoSetsReport(int total_bugs, int tested_cases){
        String rst = "";
        rst += "*********************************************************************************\n";
        rst += "*******************************HomoSet  Result***********************************\n";
        rst += "*********************************************************************************\n";
        rst += "Collect "+homoSetQueue.size()+" HomoSets\n";

        int num = 0;

        rst += "---------------------------------------------------------------------------------\n";
        //ljy--按照含有多少测试过的序列大小进行降序排序
        homoSetQueue.sort((o1,o2)->{
            int test_num1 = o1.total_already_tested_num;
            int test_num2 = o2.total_already_tested_num;
            return Integer.compare(test_num2 , test_num1);
        });

        for(HomologousSeqSet homoSet:homoSetQueue){
            num++;
            rst += "The "+num+"th homoSet:\n";
            rst += "Num of QueueEntries: "+homoSet.homoSeqSet.size()+"\n";
            if(homoSet.calculAlreadyTestedNum()){
                rst += "Num of Tested FaultSeq: "+homoSet.total_already_tested_num+"\n";
                rst += "---------------------------\n";
            }
            if(homoSet.calculTotalNewCovCon()){
                rst += "Num of Has New Coverage 's FaultSeq: "+homoSet.total_has_new_cov_num+"\n";
                rst += "Average New Coverage of this homoSet: "+homoSet.calculAverageNewCov()+"\n";
                rst += "Ratio of New Coverage's / Tested FaultSeq: "+homoSet.calculHasNewCovRatio()+"\n";
                rst += "---------------------------\n";
            }
            if(homoSet.calculHavTriggeredBugNum()){
                rst +="Num of Has Triggered bugs 's FaultSeq: "+homoSet.total_trigger_bug_num+"\n";
                rst +="Ratio of Triggered bugs 's / Tested FaultSeq: "+homoSet.calculTriggerBugRatio()+"\n";
                rst += "---------------------------\n";
            }


            rst += "------------------------------------------------------\n";
        }
        rst += compareTestedSeqsNum(total_bugs,tested_cases);
        rst += "***********************************END*******************************************\n";
        return rst;
    }
}
