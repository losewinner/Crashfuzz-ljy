package edu.iscas.CCrashFuzzer.LJYadd;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Map;
import java.util.HashMap;
import edu.iscas.CCrashFuzzer.FaultSequence;
import edu.iscas.CCrashFuzzer.FaultSequence.FaultPoint;
import edu.iscas.CCrashFuzzer.FaultSequence.FaultStat;
import edu.iscas.CCrashFuzzer.FaultSequence.FaultPos;
import edu.iscas.CCrashFuzzer.FuzzInfo;
import edu.iscas.CCrashFuzzer.QueueEntry;
import edu.iscas.CCrashFuzzer.Fuzzer;
import edu.iscas.CCrashFuzzer.IOPoint;

public class FaultSequenceParser {

    //从文件中读取故障序列文本
    public static FaultSequence readBugTriggeringSeqFromFile(String filePath) throws IOException{
        StringBuilder content = new StringBuilder();
        try(BufferedReader reader =  new BufferedReader(new FileReader(filePath))){
            String line;
            while((line = reader.readLine())!= null){
                content.append(line);
            }
        }catch(IOException e){
            System.err.println("读取文件" + filePath + "时发生错误: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
        //return parseFaultSequence(content.toString());
        //5-9测试
        System.out.println("读取的故障序列文本: " + content.toString());
        return parseFaultPoints(content.toString());
    }

    public static FaultSequence parseFaultPoints(String input) {
        FaultSequence fs = new FaultSequence();
        String[] split = input.split("FaultPoint=\\[");

        for (int i = 1; i < split.length; i++) {
            String partial = split[i];
            String full = "FaultPoint=[" + partial;

            // 截断到完整匹配的中括号闭合
            int openCount = 1;
            int j = "FaultPoint=[".length();
            while (j < full.length() && openCount > 0) {
                char ch = full.charAt(j);
                if (ch == '[') openCount++;
                else if (ch == ']') openCount--;
                j++;
            }

            // 有效的 FaultPoint 片段
            String faultPointStr = full.substring(0, j);

            // 交给各字段解析方法
            FaultPoint fp = new FaultPoint();
            fp.ioPt = parseIOPoint(faultPointStr);
            //fp.ioPtIdx = null; //该项没有对应数值
            fp.stat = parseFaultStat(faultPointStr);
            fp.pos = parseFaultPos(faultPointStr);
            fp.ioPt.pos = fp.pos;
            fp.tarNodeIp = parseTarNodeIp(faultPointStr);
            fp.actualNodeIp = parseActualNodeIp(faultPointStr);

            fs.seq.add(fp);
        }

        return fs;
    }

    private static IOPoint parseIOPoint(String text) {
        IOPoint ioPt = new IOPoint();

        ioPt.ioID = extractInt(text, "IOID=\\[(-?\\d+)\\]");
        ioPt.ip = extractString(text, "IOIP=\\[([^\\]]+)\\]");
        ioPt.appearIdx = extractInt(text, "AppearIdx=\\[(\\d+)\\]");
        ioPt.PATH = extractString(text, "Path=([^\\]]+)\\]");

        String callstackStr = extractString(text, "CallStack=\\[(.*?)\\], Path=");
        if (callstackStr != null) {
            String[] stackArray = callstackStr.split(",\\s*");
            ioPt.CALLSTACK = Arrays.asList(stackArray);
        }

        return ioPt;
    }

    private static FaultStat parseFaultStat(String text) {
        String statStr = extractString(text, "FaultStat=\\[(CRASH|REBOOT)\\]");
        return statStr != null ? FaultStat.valueOf(statStr) : null;
    }

    private static FaultPos parseFaultPos(String text) {
        String posStr = extractString(text, "FaultPos=\\[(BEFORE|AFTER)\\]");
        return posStr != null ? FaultPos.valueOf(posStr) : null;
    }

    private static String parseTarNodeIp(String text) {
        return extractString(text, "tarNodeIp=\\[([^\\]]+)\\]");
    }

    private static String parseActualNodeIp(String text) {
        String val = extractString(text, "actualNodeIp=\\[([^\\]]*)\\]");
        return "null".equals(val) ? null : val;
    }

    // 通用工具方法
    private static int extractInt(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String extractString(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

}