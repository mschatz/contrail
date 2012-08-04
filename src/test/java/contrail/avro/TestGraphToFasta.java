package contrail.avro;

import static org.junit.Assert.fail;
import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;

import org.apache.hadoop.conf.Configuration;
import org.junit.Test;

import contrail.graph.GraphNode;
import contrail.graph.GraphUtil;
import contrail.graph.SimpleGraphBuilder;
import contrail.util.FileHelper;

public class TestGraphToFasta extends GraphToFasta {

  private ArrayList<String[]> readFastq(File file) {
    BufferedReader reader;
    ArrayList<String[]> records = new ArrayList<String[]>();
    try {
      reader = new BufferedReader(new FileReader(file));

      String line = reader.readLine();
      String[] fastaRecord = new String[2];

      int recordPos = 0;
      while (line != null) {
        // Read 2 lines at a time.
        fastaRecord[recordPos] = line;
        ++recordPos;

        if (recordPos >= fastaRecord.length) {
          records.add(fastaRecord);
          fastaRecord = new String[2];
          recordPos = 0;
        }
        line = reader.readLine();
      }
    } catch (Exception exception) {
      exception.printStackTrace();
      fail("Exception occured while reading output:" + exception.getMessage());
    }
    return records;
  }

  private void assertFastaRecord(GraphNode nodeData, String[] fastaRecord) {
    assertEquals("@" + nodeData.getNodeId(), fastaRecord[0]);
    assertEquals(nodeData.getSequence().toString(), fastaRecord[1]);
  }

  @Test
  public void testRun() {
    // Create a graph and write it to a file.
    SimpleGraphBuilder builder = new SimpleGraphBuilder();
    builder.addKMersForString("ACTGGATT", 3);

    // Add some tips.
    builder.addEdge("ATT", "TTG", 2);
    builder.addEdge("ATT", "TTC", 2);

    File temp = FileHelper.createLocalTempDir();
    File avroFile = new File(temp, "graph.avro");

    ArrayList<GraphNode> nodes = new ArrayList<GraphNode>();
    nodes.addAll(builder.getAllNodes().values());
    GraphUtil.writeGraph(avroFile, nodes);

    // Run it.
    GraphToFasta stage = new GraphToFasta();
    // We need to initialize the configuration otherwise we will get an
    // exception. Normally the initialization happens in main.
    stage.setConf(new Configuration());

    File outputPath = new File(temp, "output");

    String[] args =
      {"--inputpath=" + temp.toURI().toString(),
       "--outputpath=" + outputPath.toURI().toString()};

    // Catch the following after debugging.
    try {
      stage.run(args);
    } catch (Exception exception) {
      exception.printStackTrace();
      fail("Exception occured:" + exception.getMessage());
    }

    // Read the output
    File outputFile = new File(outputPath, "part-00000");
    ArrayList<String[]> records = readFastq(outputFile);

    // Check the outputs are correct.
    for (int index = 0; index < nodes.size(); ++index) {
      assertFastaRecord(nodes.get(index), records.get(index));
    }
  }
}
