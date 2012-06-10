package contrail.avro;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

/**
 * Compress linear chains into single nodes.
 *
 * This stage consists of several mapreduce jobs which perform the following
 *   1. Find nodes which can be compressed together.
 *   2. Compress all nodes together (requires several mapreduce jobs).
 *
 * Input: The input should be an AVRO file encoding the graph; e.g records
 * are GraphNodeData.
 *
 * Alternatively, its possible to resume compressing stages. In this
 * case the input should be an AVRO file whose records are
 * CompressibleNodeData.
 */
public class CompressChains extends Stage {
  // TODO(jlewi): Should we create a separate base class for jobs which
  // run several map reduce jobs.
  private static final Logger sLogger = Logger.getLogger(CompressChains.class);
  private static PrintStream logstream;
  private static DecimalFormat df = new DecimalFormat("0.00");

  long GLOBALNUMSTEPS = 0;
  long JOBSTARTTIME = 0;

  /**
   * Sets up a print stream to use as a logger.
   *
   * The logger writes to a non HDFS path. Unless a shared filesystem (e.g NFS)
   * is used, the files will only be available on the machine where the
   * code runs.
   */
//  private void configureLogger() {
//    // TODO(jlewi): Should this be moved into Stage so we can do it for all
//    // stages.
//    // Setup to use a file appender
//    BasicConfigurator.resetConfiguration();
//
//    TTCCLayout lay = new TTCCLayout();
//    lay.setDateFormat("yyyy-mm-dd HH:mm:ss.SSS");
//
////    FileAppender fa = new FileAppender(
////        lay, ContrailConfig.localBasePath + "contrail.details.log", true);
////    fa.setName("File Appender");
////    fa.setThreshold(Level.INFO);
////    BasicConfigurator.configure(fa);
////
////    throw new RuntimeException("We should replace localBasePath with a stage option");
////    FileOutputStream logfile = new FileOutputStream(
////        ContrailConfig.localBasePath+"contrail.log", true);
////    logstream = new PrintStream(logfile);
////
////    ContrailConfig.printConfiguration();
////
////    // Time stamp
////    DateFormat dfm = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
////    sLogger.info("== Starting time " + dfm.format(new Date()) + "\n");
////    long globalstarttime = System.currentTimeMillis();
//  }

  public void start(String desc) {
    sLogger.info(desc + ":\t");

    // TODO(jlewi): What to do about the following variables.
    JOBSTARTTIME = System.currentTimeMillis();
    GLOBALNUMSTEPS++;
  }

  public void end(RunningJob job) throws IOException
  {
    long endtime = System.currentTimeMillis();
    long diff = (endtime - JOBSTARTTIME) / 1000;

    sLogger.info(job.getJobID() + " " + diff + " s");

    if (!job.isSuccessful())
    {
      System.out.println("Job was not successful");
      System.exit(1);
    }
  }

//  public static void msg(String msg)
//  {
//    sLogger.info(msg)
////    logstream.print(msg);
////    System.out.print(msg);
//  }

  /**
   * Maximally compress chains.
   *
   * @param input_path: The directory to act as input. This could
   *   be an uncompressed graph or it could be the output of PairMergeAvro
   *   if we are continuing a previous compression.
   * @param temp_path: The parent directory for the outputs from each
   *   stage of compression.
   * @param final_path: The directory where the final output should be stored.
   * @throws Exception
   */
  private void compressChains(
      String input_path, String temp_path, String final_path) throws Exception {
    CompressibleAvro compress = new CompressibleAvro();

    // TODO(jlewi) Need to add the code for QuickMarkAvro
    // QuickMarkAvro qmark   = new QuickMarkAvro();
    QuickMergeAvro qmerge = new QuickMergeAvro();

    PairMarkAvro pmark   = new PairMarkAvro();
    PairMergeAvro pmerge = new PairMergeAvro();

    int stage = 0;
    long compressible = 0;

    // The minimum number of nodes for doing parallel compressions
    final long LOCALNODES = (Long) stage_options.get("localnodes");

    // When formatting the step as a string we want to zero pad it
    DecimalFormat sf = new DecimalFormat("00");

    //RunningJob job = null;

    // Keep track of the path from the latest step as this will be
    // the input to the next step.
    String latest_path = null;

    // TODO(jlewi): How should RESTART_COMPRESS etc... be encoded.
    // How about just adding a command line option resume?
    // By default, we could always make the first iteration parallel
    // if resuming the compression. We could add a second flag to allow
    // this to be overwritten
    // To determine the step number we should probably parse the directory,
    // or else make it a command line option.
    if (false) {
      // TODO(jlewi): This if block represents the case where
      // we resume Mark/Merge iterations after some previous processing
      // Compressible nodes should already be marked so we don't
      // need to run compression.
      throw new NotImplementedException("Need to update this code");
      //compressible = ContrailConfig.RESTART_COMPRESS_REMAIN;

      //sLogger.info("Restarting compression after stage " + stage + ":");

      //ContrailConfig.RESTART_COMPRESS = 0;
      //ContrailConfig.RESTART_COMPRESS_REMAIN = 0;
    }
    else {
      // Mark compressible nodes
      start("Compressible");

      // Make a shallow copy of the stage options so we can overwrite some
      // of the options.
      Map<String, Object> substage_options =
          (HashMap<String, Object>) stage_options.clone();
      substage_options.put("inputpath", input_path);

      latest_path =
          (new File(temp_path, "step_" + sf.format(stage))).getPath();

      substage_options.put("outputpath", latest_path);
      compress.setOptionValues(substage_options);
      RunningJob job = compress.runJob();
      compressible = counter(job, GraphCounters.compressible_nodes);
      end(job);
    }

    sLogger.info("  " + compressible + " compressible\n");
    long lastremaining = compressible;

    while (lastremaining > 0) {
      int prev = stage;
      stage++;

      // Input path for marking nodes to be merged.
      String mark_input  = latest_path;

      // The directory for this step.
      String step_dir =
          new File(temp_path, "step_" + sf.format(stage)).getPath();
      // The path containing the graph marked for merging.
      String marked_graph_path = new File(step_dir, "marked_graph").getPath();

      // The path for the merged graph.
      String merged_graph_path = new File(step_dir, "merged_graph").getPath();

      latest_path = merged_graph_path;
      long remaining = 0;

      // TODO(jlewi): Should we make local nodes a stage variable?
      if (lastremaining < LOCALNODES) {
        throw new RuntimeException("This code needs to be updated");
//        // Send all the compressible nodes to the same machine for serial processing
//        start("  QMark " + stage);
//        job = qmark.run(input, input0);
//        end(job);
//
//        sLogger.info("  " + counter(job, "compressibleneighborhood") + " marked\n");
//
//        start("  QMerge " + stage);
//        job = qmerge.run(input0, output);
//        end(job);
//
//        remaining = counter(job, "needcompress");
      }
      else
      {
        // Use the randomized algorithm
        double rand = Math.random();

        {
          start("Mark" + stage);
          Map<String, Object> mark_options = new HashMap<String, Object>();
          mark_options.put("inputpath", mark_input);
          mark_options.put("outputpath", marked_graph_path);

          Long seed = (long)(rand*10000000);
          mark_options.put("randseed", seed);
          pmark.setOptionValues(mark_options);
          RunningJob job = pmark.runJob();
          end(job);

          sLogger.info("  " + counter(job, GraphCounters.num_nodes_to_merge) +
              " marked\n");
        }
        {
          start("  Merge " + stage);
          Map<String, Object> mark_options = new HashMap<String, Object>();
          mark_options.put("inputpath", marked_graph_path);
          mark_options.put("outputpath", merged_graph_path);
          mark_options.put("K", stage_options.get("K"));
          pmerge.setOptionValues(mark_options);
          RunningJob job = pmerge.runJob();
          end(job);
          remaining = counter(job,GraphCounters.pair_merge_compressible_nodes);
        }
      }

      JobConf job_conf = new JobConf(CompressChains.class);
      FileSystem.get(job_conf).delete(new Path(mark_input), true);
      FileSystem.get(job_conf).delete(new Path(marked_graph_path), true);

      String percchange =
          df.format((lastremaining > 0) ? 100*(remaining - lastremaining) /
              lastremaining : 0);
      sLogger.info("  " + remaining + " remaining (" + percchange + "%)\n");

      lastremaining = remaining;
    }

    JobConf job_conf = new JobConf(CompressChains.class);
    sLogger.info("Save result to " + final_path + "\n\n");
    FileUtil.saveResult(
        job_conf, latest_path, final_path);
  }

  /**
   * Return the value of the specified counter in the job.
   * @param job
   * @param group
   * @param tag
   * @return
   * @throws IOException
   */
  private long counter(RunningJob job, GraphCounters.CounterName counter)
      throws IOException {
    return job.getCounters().findCounter(counter.group, counter.tag).getValue();
  }

  /**
   * Get the options required by this stage.
   */
  protected List<Option> getCommandLineOptions() {
    List<Option> super_options = super.getCommandLineOptions();

    HashMap<String, Option> alloptions = new HashMap<String, Option>();
    for (Option option: super_options) {
      alloptions.put(option.getArgName(), option);
    }

    // We add all the options for the stages we depend on.
    Stage[] substages =
      {new CompressibleAvro(), new QuickMergeAvro(), new PairMarkAvro(),
       new PairMergeAvro()};

    for (Stage stage: substages) {
      List<Option> stage_options = stage.getCommandLineOptions();
      for (Option option: stage_options) {
        if (alloptions.containsKey(option.getOpt())) {
          continue;
        }
        alloptions.put(option.getOpt(), option);
      }
    }

    List<Option> options = new ArrayList<Option>();
    options.addAll(alloptions.values());

    // Add options specific to this stage.
    options.add(OptionBuilder.withArgName("localnodes").hasArg().
        withDescription(
            "If the number of compressible nodes is less than this value " +
            "then all compressible nodes get sent to a single worker " +
            "for compression.").create("localnodes"));
    return options;
  }

  protected void initializeDefaultOptions() {
    super.initializeDefaultOptions();
    default_options.put("localnodes", new Long(1000));
  }

  @Override
  protected void parseCommandLine(CommandLine line) {
    super.parseCommandLine(line);

    // Parse the options for each stage.
    Stage[] substages =
      {new CompressibleAvro() , new QuickMergeAvro(), new PairMarkAvro(),
       new PairMergeAvro()};
    HashMap<String, HashMap<String, Object>> substage_options =
        new HashMap<String, HashMap<String, Object>>();

    for (Stage stage: substages) {
      stage.parseCommandLine(line);
      substage_options.put(stage.getClass().getName(), stage.stage_options);

      // TODO(jlewi): It would be better to allow distinct maps
      // for each stage. The problem with that is figuring out how the
      // substage options would be set when they aren't passed as a set
      // of string arguments.
      stage_options.putAll(stage.stage_options);
    }

    // Get the options which are specific to this stage.
    if (line.hasOption("localnodes")) {
      stage_options.put(
          "localnodes", Long.parseLong(line.getOptionValue("localnodes")));
    }
  }

  @Override
  public RunningJob runJob() throws Exception {
    String[] required_args = {"inputpath", "outputpath", "localnodes", "K"};
    checkHasOptionsOrDie(required_args);

    String input_path = (String) stage_options.get("inputpath");
    String output_path = (String) stage_options.get("outputpath");
    // TODO(jlewi): Is just appending "temp" to the output path
    // really a good idea?
    String temp_path = new Path(output_path, "temp").toString();

//    long K = (Long)stage_options.get("K");
//
//    sLogger.info(" - input: "  + inputPath);
//    sLogger.info(" - output: " + outputPath);
//    sLogger.info(" - K: " + K);
//
//    JobConf conf = new JobConf(PairMergeAvro.class);
//    conf.setJobName("PairMergeAvro " + inputPath + " " + K);
//
//    initializeJobConfiguration(conf);
//
//    FileInputFormat.addInputPath(conf, new Path(inputPath));
//    FileOutputFormat.setOutputPath(conf, new Path(outputPath));
//
//    NodeInfoForMerge merge_info = new NodeInfoForMerge();
//    Pair<CharSequence, NodeInfoForMerge> map_output =
//        new Pair<CharSequence, NodeInfoForMerge> ("", merge_info);
//
//    CompressibleNodeData compressible_node = new CompressibleNodeData();
//    AvroJob.setInputSchema(conf, merge_info.getSchema());
//    AvroJob.setMapOutputSchema(conf, map_output.getSchema());
//    AvroJob.setOutputSchema(conf, compressible_node.getSchema());
//
//    AvroJob.setMapperClass(conf, PairMergeMapper.class);
//    AvroJob.setReducerClass(conf, PairMergeReducer.class);

    if (stage_options.containsKey("writeconfig")) {
      // TODO(jlewi): Can we write the configuration for this stage like
      // other stages or do we need to do something special?
      throw new NotImplementedException(
          "Support for writeconfig isn't implemented yet for compresschains");
    } else {
      // Delete the output directory if it exists already
//      Path out_path = new Path(outputPath);
//      if (FileSystem.get(conf).exists(out_path)) {
//        // TODO(jlewi): We should only delete an existing directory
//        // if explicitly told to do so.
//        sLogger.info("Deleting output path: " + out_path.toString() + " " +
//            "because it already exists.");
//        FileSystem.get(conf).delete(out_path, true);
//      }
//
      long starttime = System.currentTimeMillis();
      compressChains(input_path, temp_path, output_path);
      long endtime = System.currentTimeMillis();

      float diff = (float) ((endtime - starttime) / 1000.0);
      System.out.println("Runtime: " + diff + " s");
    }
    return null;
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new Configuration(), new PairMergeAvro(), args);
    System.exit(res);
  }
}
