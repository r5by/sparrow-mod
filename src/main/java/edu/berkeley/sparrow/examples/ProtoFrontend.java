package edu.berkeley.sparrow.examples;

import edu.berkeley.sparrow.api.SparrowFrontendClient;
import edu.berkeley.sparrow.daemon.scheduler.SchedulerThrift;
import edu.berkeley.sparrow.thrift.FrontendService;
import edu.berkeley.sparrow.thrift.TFullTaskId;
import edu.berkeley.sparrow.thrift.TTaskSpec;
import edu.berkeley.sparrow.thrift.TUserGroupInfo;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ProtoFrontend implements FrontendService.Iface {

    /**
     * Default application name.
     */
    public static final String APPLICATION_ID = "sleepApp";
    private static final Logger LOG = Logger.getLogger(ProtoFrontend.class);


    /**
     * Host and port where scheduler is running.。/
     *
     */
    public static final String SCHEDULER_HOST = "scheduler_host";
    public static final String DEFAULT_SCHEDULER_HOST = "localhost";
    public static final String SCHEDULER_PORT = "scheduler_port";
    public static final String TOTAL_NUM_OF_REQUESTS = "total_num_of_requests";

    /**
     * trace file config.
     */
    public static final String TR_PATH = "tr_path";

    /* For multi-schedulers */
    public static final String SCHEDULER_ID = "scheduler_id";
    public static final String SCHEDULER_SIZE = "scheduler_size";

    /* For Huiyang's experiments */
//    public static final String TR_CUTOFF = "tr_cutoff";
//    private double cutoff;

    private static final TUserGroupInfo USER = new TUserGroupInfo();

    private SparrowFrontendClient client;

    private long totalNumberOfRequests;
    private long completedRequestsCount;

    private class JobLaunchRunnable implements Runnable {
        //        private int requestId;
        private double arrivalInterval;
        private double averageTasksD;
        private List<Double> tasksDList;

        public JobLaunchRunnable(double arrivalInterval, double avgTasksD, List<Double> tasks) {
//            this.requestId = requestId;
            this.arrivalInterval = arrivalInterval;
            this.averageTasksD = avgTasksD;
            tasksDList = new ArrayList<Double>(tasks);
        }

        @Override
        public void run(){
            // Generate tasks in the format expected by Sparrow. First, pack task parameters.
//            boolean shortjob;

            List<TTaskSpec> tasks = new ArrayList<TTaskSpec>();
            for (int taskId = 0; taskId < tasksDList.size(); taskId++) {
                TTaskSpec spec = new TTaskSpec();
                ByteBuffer message = ByteBuffer.allocate(8);

                spec.setTaskId(Integer.toString(taskId));
                long tasksDListLong = Math.round(tasksDList.get(taskId));
                message.putLong(tasksDListLong);
                spec.setMessage(message.array());
                tasks.add(spec);
            }
            long start = System.currentTimeMillis();

            try {
                client.submitJob(APPLICATION_ID, tasks, USER);
            } catch (TException e) {
                LOG.error("Scheduling request failed!", e);
            }
            long end = System.currentTimeMillis();
            LOG.debug("Scheduling request duration " + (end - start));
        }
    }

    @Override
    public void frontendMessage(TFullTaskId taskId, int status, ByteBuffer message) throws TException {
        LOG.debug("Task: " + taskId.getTaskId() + " for request: " + taskId.requestId + " has completed!");
        if(status == 1) {
            completedRequestsCount++;
            LOG.debug("All tasks for request: " + taskId.requestId + " have been completed. The total elapsed time is: " + message.getLong(message.position()) + " ms");
            String requestInfo = "All tasks for request: " + taskId.requestId + " have been completed. The total elapsed time is: " + message.getLong(message.position()) + " ms";
            CreateNewTxt(requestInfo);
        }
    }

    /*Output txt file*/
    public void CreateNewTxt(String requestInfo){
        BufferedWriter output = null;
        try {
            File file = new File("requestInfo.txt");
            output = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), "utf-8"));
            output.write(requestInfo+"\r\n");
            output.flush();
            output.close();
        } catch ( IOException e ) {
            e.printStackTrace();
        }
    }

    public void start(String[] args) {
        try {
            OptionParser parser = new OptionParser();
            parser.accepts("c", "configuration file").withRequiredArg().ofType(String.class);
            parser.accepts("help", "print help statement");
            OptionSet options = parser.parse(args);

            if (options.has("help")) {
                parser.printHelpOn(System.out);
                System.exit(-1);
            }

            // Logger configuration: log to the console
            BasicConfigurator.configure();
            LOG.setLevel(Level.DEBUG);

            Configuration conf = new PropertiesConfiguration();

            if (options.has("c")) {
                String configFile = (String) options.valueOf("c");
                conf = new PropertiesConfiguration(configFile);
            }

            String trPath = conf.getString(TR_PATH);
            int counter = 0;
            int schedulerId = conf.getInt(SCHEDULER_ID);
            int schedulerSize = conf.getInt(SCHEDULER_SIZE);

//            Double traceCutOff = conf.getDouble(TR_CUTOFF, TR_CUTOFF_DEFAULT);
//            traceCutOffMilliSec = traceCutOff.longValue();

            int schedulerPort = conf.getInt(SCHEDULER_PORT,
                    SchedulerThrift.DEFAULT_SCHEDULER_THRIFT_PORT);
            String schedulerHost = conf.getString(SCHEDULER_HOST, DEFAULT_SCHEDULER_HOST);
//            cutoff = conf.getDouble(TR_CUTOFF);
            totalNumberOfRequests = conf.getLong(TOTAL_NUM_OF_REQUESTS);


            client = new SparrowFrontendClient();
            client.initialize(new InetSocketAddress(schedulerHost, schedulerPort), APPLICATION_ID, this);

            //set experiment count
            completedRequestsCount = 0;

            FileInputStream inputStream = new FileInputStream(trPath);
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

            String str = null;

            int requestId = 0;
            Double arrivalInterval = 0.0;
            double exprTime = 0.0;
            Double averageDuriationMilliSec;
            long arrivalIntervalinMilliSec = 0;
            List tasks = new ArrayList();

            ScheduledThreadPoolExecutor taskLauncher = new ScheduledThreadPoolExecutor(1);

            while((str = bufferedReader.readLine()) != null)
            {
                if(counter % schedulerSize == schedulerId) {
                    str = str+"\r\n";
                    String[] SubmissionTime =  str.split("\\s{1,}|\t");
                    arrivalInterval = Double.parseDouble(SubmissionTime[0]);

                    arrivalIntervalinMilliSec = Double.valueOf(arrivalInterval * 1000).longValue();

                    averageDuriationMilliSec = Double.parseDouble(SubmissionTime[2]) * 1000;

                    String[] dictionary = str.split("\\s{2}|\t");
                    //tasks = null;
                    for(int i = 1;i<dictionary.length-1;i++){
                        //change second to milliseconds
                        double taskDinMilliSec = Double.valueOf(dictionary[i]) * 1000;
                        tasks.add(taskDinMilliSec);

                    }

                    //Estimated experiment duration
                    exprTime += averageDuriationMilliSec * tasks.size();

                    ProtoFrontend.JobLaunchRunnable runnable = new JobLaunchRunnable(arrivalIntervalinMilliSec, averageDuriationMilliSec,tasks);
                    taskLauncher.schedule(runnable,  arrivalIntervalinMilliSec, TimeUnit.MILLISECONDS);

                    requestId++;
                    System.out.println(tasks);
                    tasks.clear();
                }

                counter++;
            }
            System.out.println(tasks);
            inputStream.close();
            bufferedReader.close();

            long startTime = System.currentTimeMillis();
            LOG.debug("sleeping");
//            while (System.currentTimeMillis() < startTime + exprTime) {
//                Thread.sleep(100);
//            }
            while(totalNumberOfRequests != completedRequestsCount) {
                Thread.sleep(100);
            }
            taskLauncher.shutdown();
            LOG.debug("Experiment Completed!!");
            client.close();
        } catch (Exception e) {
            LOG.error("Fatal exception", e);
        }
    }

    public static void main(String[] args) {
        new ProtoFrontend().start(args);
    }

}
