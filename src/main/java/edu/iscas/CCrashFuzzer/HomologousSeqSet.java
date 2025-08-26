package edu.iscas.CCrashFuzzer;


import java.util.ArrayList;
import java.util.List;

//ljy--确定同源序列集合
//定义：同源指的是，在一个故障序列A的基础上，变异不超过两次（包含两次）的所有故障序列，皆同源于序列A
//具体规则：一个节点A的子结点们，以及其子结点的所有子结点，视为同源。
// 但是，节点A的子结点，以及子结点的子结点，将不会在成为后面层节点的源头。
// 即，每个节点只会出现在一个同源集合中。
//重点：变异的源头，至少是变异过一次的故障序列（即无故障序列的QueueEntry将不会成为任何故障序列的源头）

public class HomologousSeqSet {
    public List<QueueEntry> homoSeqSet;      //ljy--同源的序列集合
    public int original_id;                 //ljy--同源于哪个序列，使用getFaultSeqID
    public int total_already_tested_num;  //ljy--同源集合中，已被测试过的故障序列总数
    public int total_trigger_bug_num;     //ljy--同源集合中，触发了bug的故障序列总数
    public int total_has_new_cov_num;     //ljy--同源集合中，覆盖新的代码路径的故障序列的总数
    public int total_new_cov_contribution;//ljy--拥有新覆盖率的故障序列们的新增覆盖率总数
    public double average_new_cov_contribution; //ljy--平均新增覆盖率
    public double trigger_bug_rate;         //ljy--集合中，触发bug的故障序列占已被测试过的故障序列总数的比率。
    //ljy--同源序列集合初始化
    public HomologousSeqSet(){
        homoSeqSet = new ArrayList<>();
        total_already_tested_num = 0;
        total_trigger_bug_num = 0;
        total_has_new_cov_num = 0;
        total_new_cov_contribution = 0;
        trigger_bug_rate = 0;
    }

    //ljy--更新同源序列中的对应的QueueEntry
    public void updateQueueEntry(QueueEntry entry){
        //ljy--这里不能直接用entry去找，因为经过了一次测试以后，entry里面的很多东西都换新了，直接查是查不到的
        for(int i = 0;i<homoSeqSet.size();i++){
            QueueEntry tmp_e = homoSeqSet.get(i);
            if(tmp_e.faultSeq.getFaultSeqID() == entry.faultSeq.getFaultSeqID()){
                int tmp_mutate_depth = tmp_e.mutate_depth;
                int tmp_original_id = tmp_e.original_id;
                tmp_e = entry;
                tmp_e.original_id = tmp_original_id;
                tmp_e.mutate_depth = tmp_mutate_depth;
                homoSeqSet.set(i,tmp_e);
            }
        }

    }
    //ljy--计算已经被测试过的故障序列总数（适用于任何时候调用）
    public boolean calculAlreadyTestedNum(){
        if(homoSeqSet == null||homoSeqSet.isEmpty()){
            return false;
        }
        int tmp_total_already_tested_num = 0;
        for(QueueEntry e:homoSeqSet){
            if(e.was_tested) {
                tmp_total_already_tested_num++;
            }
        }
        total_already_tested_num =Math.max(total_already_tested_num, tmp_total_already_tested_num);
        return true;
    }

    //ljy--计算总的新增覆盖率(任何时候可调用）,以及有多少个故障序列覆盖了新代码路径，以及平均新增覆盖率
    public boolean calculTotalNewCovCon(){
        if(homoSeqSet == null || homoSeqSet.isEmpty()){
            return false;
        }
        int tmp_total_new_cov_contribution = 0;
        int tmp_total_has_new_cov_num = 0;
        for(QueueEntry e:homoSeqSet){
            if(e.has_new_cov) {
                tmp_total_has_new_cov_num++;
                tmp_total_new_cov_contribution += e.new_cov_contribution;
            }
        }
        total_new_cov_contribution = Math.max(total_new_cov_contribution,tmp_total_new_cov_contribution);
        total_has_new_cov_num = Math.max(total_has_new_cov_num,tmp_total_has_new_cov_num);
        return true;
    }

    //ljy--计算平均新增覆盖率(任何时候可调用）
    public double calculAverageNewCov(){
        if(total_has_new_cov_num !=0){
            average_new_cov_contribution = (double) total_new_cov_contribution/total_has_new_cov_num;
            return average_new_cov_contribution;
        }
        return -1;

    }

    //ljy--计算触发bug比例：
    public double calculTriggerBugRate(){
        if(total_already_tested_num !=0){
            trigger_bug_rate = (double) total_trigger_bug_num /total_already_tested_num;
            return trigger_bug_rate;
        }
        return -1;

    }

}
