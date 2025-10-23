package edu.iscas.CCrashFuzzer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import edu.iscas.CCrashFuzzer.utils.FileUtil;

public class CoverageCollector {
	//ljy--记录尚未在崩溃中出现的代码块
	public static byte[] virgin_bits;    /* Bits we haven't seen in crashes  */
	static byte[] virgin_tmout;
	static byte[] virgin_crash;

	//ljy--字节数组trace_bits存储当前执行路径覆盖的代码块信息
	public static byte[] trace_bits;//store covered bits in a run
	
    public int actualSize(){
		// return Conf.MAP_SIZE / 8 + (Conf.MAP_SIZE % 8 == 0 ? 0 : 1);
//		return Conf.MAP_SIZE;
    	return _get_map_size();
	}

    public static int _get_map_size() {
    	int MAP_SIZE_POW2 = 16;
    	int MAP_SIZE = (1 << MAP_SIZE_POW2);
    	return MAP_SIZE;
    }

	public CoverageCollector() {
		//ljy--这是类初始化
		virgin_bits = new byte[actualSize()];
        Arrays.fill(virgin_bits, (byte)0);

		trace_bits = new byte[actualSize()];
		Arrays.fill(trace_bits, (byte)0);
	}
	/* Check if the current execution path brings anything new to the table.
	   Update virgin bits to reflect the finds. Returns 1 if the only change is
	   the hit-count for a particular tuple; 2 if there are new tuples seen. 
	   Updates the map, so subsequent calls will always return 0.
	   This function is called after every exec() on a fairly large buffer, so
	   it needs to be fast. We do this in 32-bit and 64-bit flavors. */

	//my return the new covered bits.

	//ljy--has_new_bits()方法检查当前执行是否带来新的代码覆盖
	//ljy--最外层的函数，被Fuzzer.java调用
	public int has_new_bits() {
		int finds= 0;
		int curCovCounts = 0;
		for(int i = 0; i< trace_bits.length; i++) {
			if(trace_bits[i]>0 && virgin_bits[i] ==0) {
				finds++;
				virgin_bits[i] = trace_bits[i];
//				System.out.println("Got new edge:"+i+".");
			}
			if(virgin_bits[i] > 0) {
				curCovCounts ++;
			}
		}
		System.out.println("Got "+finds+" new edges.");
		
		int rst = 0;
		for(int i = 0; i< virgin_bits.length; i++) {
			if(virgin_bits[i] > 0) {
				rst++;
			}
		}

		if(finds > 0) {
			//ljy--将当前代码未覆盖情况写入到文件中进行持久化，为了下一轮测试再拿出来对比
			write_bitmap(virgin_bits, FileUtil.root+FileUtil.virgin_map_file);
			int key = (int) (FuzzInfo.getUsedSeconds()/(FuzzInfo.reportWindow*60));
			FuzzInfo.timeToTotalCovs.put(key, curCovCounts);
			FuzzInfo.lastNewCovTime = FuzzInfo.getUsedSeconds();
		}
		System.out.println("Current covered edges is "+rst);
		Stat.log("Covered "+finds+" new code blocks!!!!!!!!!!!!!!!!!!!");
		return finds;
	}

	//计算已覆盖的代码块数量
	public static int coveredBlocks(byte[] bytes) {
		int rst = 0;
		for(int i = 0; i< bytes.length; i++) {
			if(bytes[i] > 0) {
				rst++;
			}
		}
		return rst;
	}
	
	public static int has_new_cov(byte[] virgin,byte[] traced) {
		int sum=0;
		int c=0;
		for(int i=0;i<virgin.length;i++) {
		  int newCov = (virgin[i]&traced[i]);
		  c=newCov;
		  while(c!=0) {
			  c&=(c-1);
			  sum++;
		  }
		  virgin[i] = (byte) (newCov^virgin[i]);
		}
		return sum;
	}
	
	/* Write bitmap to file. The bitmap is useful mostly for the secret
	   -B option, to focus a separate fuzzing session on a particular
	   interesting input without rediscovering all the others. */

	public void write_bitmap(byte[] bytes, String fname) {
		FileOutputStream out;
		try {
			File f = new File(fname);
			if(!f.exists()) {
				f.getParentFile().mkdir();
			}
			
			out = new FileOutputStream(fname);
//			System.out.println("CrashFuzz: mark covered blocks"+coveredBlocks(data)+" | "+coveredBlocks2(data));
			out.write(bytes, 0, bytes.length);
			out.flush();
			out.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public byte[] load_a_bitmap(String fname) {
		byte[] map = new byte[actualSize()];
		File coverFile = new File(fname);
		try {
			if(coverFile.exists()) {//load last trace map and combine
				FileInputStream tracedFileIn = new FileInputStream(coverFile);
				tracedFileIn.read(map);
				tracedFileIn.close();
			}
		} catch(IOException e) {
			e.printStackTrace();
		}
		return map;
	}
	
	/* Read bitmap from file. This is for the -B option again. */

	//ljy--这里统计的是本次测试一共覆盖了多少代码基本块（并非相比目前最大覆盖率的新覆盖）
	public void read_bitmap(String fname) {
		Arrays.fill(trace_bits, (byte)0);
		//load trace bits
		List<File> traces = new ArrayList<File>();
		loadTrace(traces, fname, "fuzzcov");
		//ljy--读取的是tmp文件加下的cov文件夹的各个服务器下的fuzzcov文件
		System.out.println("Got "+traces.size()+" coverage files.");
		int edges = 0;
		for(File f:traces) {
			try {
				byte[] data = new byte[_get_map_size()];
				Arrays.fill(data, (byte)0);
				FileInputStream coverFileIn = new FileInputStream(f);
				coverFileIn.read(data);
				for(int i = 0; i< trace_bits.length; i++) {
					trace_bits[i] = (byte) Math.max(trace_bits[i], data[i]);
				}
				coverFileIn.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		for(int i = 0; i< trace_bits.length; i++) {
			if(trace_bits[i] > 0) {
				edges++;
			}
		}

		//ljy--edges统计的是被覆盖的代码块的数量
		System.out.println("Got "+edges+" edges for this trace.");		
		Stat.log("read_bitmap-Got "+edges+" covered blocks!");
	}
	
	public static List<File> loadTrace(List<File> fileList, String trace_dir, String trace_file) {
        File file = new File(trace_dir);
        if(file.isFile() && file.getName().equals(trace_file)) {
        	fileList.add(file);
        	return fileList;
        }
        
        File[] files = file.listFiles();// 获取目录下的所有文件或文件夹
        if (files == null) {// 如果目录为空，直接退出
            return fileList;
        }
        // 遍历，目录下的所有文件
        for (File f : files) {
            if (f.isFile() && f.getName().equals(trace_file)) {
//            	System.out.println("Find cov file "+f.getAbsolutePath());
                fileList.add(f);
            } else if (f.isDirectory()) {
//                System.out.println(f.getAbsolutePath());
                loadTrace(fileList, f.getAbsolutePath(), trace_file);
            }
        }
        return fileList;
	}
	
	/* When we bump into a new path, we call this to see if the path appears
	   more "favorable" than any of the existing ones. The purpose of the
	   "favorables" is to have a minimal set of paths that trigger all the bits
	   seen in the bitmap so far, and focus on fuzzing them at the expense of
	   the rest.
	   The first step of the process is to maintain a list of top_rated[] entries
	   for every byte in the bitmap. We win that slot if there is no previous
	   contender, or if the contender has a more favorable speed x size factor. */

	public void update_bitmap_score(QueueEntry q) {

	}
	
	/* The second part of the mechanism discussed above is a routine that
	   goes over top_rated[] entries, and then sequentially grabs winners for
	   previously-unseen bytes (temp_v) and marks them as favored, at least
	   until the next run. The favored entries are given more air time during
	   all fuzzing steps. */

	public void cull_queue() {

	}

	/* Examine map coverage. Called once, for first test case. */

	public void check_map_coverage() {

	}
	
	/* Calculate case desirability score to adjust the length of havoc fuzzing.
	   A helper function for fuzz_one(). Maybe some of these constants should
	   go into config.h. */

	static int calculate_score(QueueEntry q) {
		return 100;
	}
}
