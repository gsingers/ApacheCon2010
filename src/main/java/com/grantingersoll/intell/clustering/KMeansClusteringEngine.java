package com.grantingersoll.intell.clustering;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.apache.mahout.clustering.Cluster;
import org.apache.mahout.clustering.WeightedVectorWritable;
import org.apache.mahout.clustering.kmeans.KMeansDriver;
import org.apache.mahout.common.Pair;
import org.apache.mahout.common.distance.CosineDistanceMeasure;
import org.apache.mahout.common.distance.DistanceMeasure;
import org.apache.mahout.math.NamedVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.apache.mahout.utils.vectors.TermInfo;
import org.apache.mahout.utils.vectors.io.JWriterTermInfoWriter;
import org.apache.mahout.utils.vectors.io.SequenceFileVectorWriter;
import org.apache.mahout.utils.vectors.io.VectorWriter;
import org.apache.mahout.utils.vectors.lucene.CachedTermInfo;
import org.apache.mahout.utils.vectors.lucene.LuceneIterable;
import org.apache.mahout.utils.vectors.lucene.TFDFMapper;
import org.apache.mahout.vectorizer.TFIDF;
import org.apache.mahout.vectorizer.Weight;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrEventListener;
import org.apache.solr.handler.clustering.DocumentClusteringEngine;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexReader;
import org.apache.solr.search.SolrIndexSearcher;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/**
 * Cluster the whole Lucene index.  The {@link org.apache.solr.core.SolrEventListener} side of this implementation
 * is responsible for getting the indexed clustered.  The {@link org.apache.solr.handler.clustering.DocumentClusteringEngine} side
 * of this is responsible.
 *
 * Eventually, this should evolve to just send the clustering job off to Hadoop, but for now we'll just do everything local
 */
public class KMeansClusteringEngine extends DocumentClusteringEngine implements SolrEventListener, KMeansClusteringParams {
  

  protected File clusterBaseDir;
  protected String inputField;
  protected SolrCore core;

  private DistanceMeasure measure;
  private double convergence = 0.001;
  private int maxIters = 20;

  //private Object swapContext = new Object();
  private ExecutorService execService;
  protected Future<ClusterJob> theFuture;
  private ClusterJob lastSuccessful;
  private boolean cacheClusters = true;//lazy load the data structures representing the clusters
  private boolean cachePoints = true;


  public KMeansClusteringEngine() {
    execService = Executors.newSingleThreadExecutor();
    measure = new CosineDistanceMeasure();
  }

  @Override
  public NamedList cluster(SolrParams params) {
    NamedList result = new NamedList();
    //check to see if we have new results
    try {
      if (theFuture != null){
        ClusterJob job = theFuture.get(1, TimeUnit.MILLISECONDS);
        if (job != null){
          //we have new results
          //clean up the old ones
          //TODO: clean up the old dirs before switching lastSuccessful
          lastSuccessful = job;
        }
      }

    } catch (InterruptedException e) {
      log.error("Exception", e);
    } catch (ExecutionException e) {
      log.error("Exception", e);
    } catch (TimeoutException e) {
      log.error("Exception", e);
    }
    if (lastSuccessful != null){//we have clusters
      if (params.getBool(LIST_CLUSTERS)){
        NamedList nl = new NamedList();
        result.add("all", nl);
        if (lastSuccessful)
      }
      String docId = params.get(IN_CLUSTER);
      if (docId != null){

      }
      String clusterId = params.get(LIST_POINTS);
      if (clusterId != null){

      }
    }
    return result;
  }

  @Override
  public NamedList cluster(DocSet docSet, SolrParams solrParams) {
    NamedList result = null;
    //TODO: Schedule these docs for the future and return a key by which an application can pick them up later
    return result;
  }


  @Override
  public String init(NamedList config, SolrCore core) {
    String result = super.init(config, core);
    SolrParams params = SolrParams.toSolrParams(config);
    this.core = core;
    String dirStr = params.get("dir");
    clusterBaseDir = new File(dirStr);
    clusterBaseDir.mkdirs();
    inputField = params.get("inputField");
    String distMeas = params.get("distanceMeasure");
    Class distClass = core.getResourceLoader().findClass(distMeas);

    try {
      measure = (DistanceMeasure) distClass.newInstance();
    } catch (InstantiationException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Unable to load measure class", e);
    } catch (IllegalAccessException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Unable to load measure class", e);
    }
    convergence = params.getDouble("convergence", 0.001);
    maxIters = params.getInt("maxIterations", 20);
    cacheClusters = params.getBool("cacheClusters", true);
    cachePoints = params.getBool("cachePoints", true);
    return result;
  }

  //Event Listener

  public void postCommit() {
    //nothing to do here, b/c we need the new searcher
  }

  public void newSearcher(SolrIndexSearcher newSearcher, SolrIndexSearcher currentSearcher) {

    //go and do the clustering.  First, we need to export the fields
    SchemaField keyField = core.getSchema().getUniqueKeyField();
    //TODO: should we prevent overlaps here if there are too many commits?  Clustering isn't something that has to be fresh all the time
    // and we likely can't sustain that anyway.
    if (keyField != null) {//we must have a key field
      //do this part synchronously here, and then spawn off a thread to do the clustering, otherwise it will take too long
      String idName = keyField.getName();
      Weight weight = new TFIDF();
      SolrIndexReader reader = newSearcher.getReader();
      try {
        TermInfo termInfo = new CachedTermInfo(reader, "content", 1, 100);
        LuceneIterable li = new LuceneIterable(reader, idName, inputField, new TFDFMapper(reader, weight, termInfo));
        Date now = new Date();
        String jobDir = clusterBaseDir.getAbsolutePath() + File.pathSeparator + "clusters-" + now.getTime();
        log.info("Dumping {} to {}", inputField, clusterBaseDir);
        File outFile = new File(jobDir, "index-" + inputField + ".vec");
        VectorWriter vectorWriter = getSeqFileWriter(outFile.getAbsolutePath());
        long numDocs = vectorWriter.write(li, Integer.MAX_VALUE);
        vectorWriter.close();
        log.info("Wrote: {} vectors", numDocs);
        File dictOutFile = new File(jobDir, "dict-" + inputField + ".txt");
        log.info("Dictionary Output file: {}", dictOutFile);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(dictOutFile), Charset.forName("UTF8")));
        JWriterTermInfoWriter tiWriter = new JWriterTermInfoWriter(writer, "\t", inputField);
        tiWriter.write(termInfo);
        tiWriter.close();
        writer.close();
        //OK, the dictionary is dumped, now we can cluster, do this via a thread in the background.
        //when it's done, we can switch to it
        theFuture = execService.submit(new ClusterCallable(new ClusterJob(
                                  jobDir,
                                  new Path(outFile.getAbsolutePath()),
                                  new Path(jobDir),
                                  new Path(jobDir),
                                  new Path(dictOutFile.getAbsolutePath())
                )));
      } catch (IOException e) {
        log.error("Exception", e);
      }
    }
  }

  private class ClusterJob{
    String jobDir;
    Path input, clustersIn, output, dictionary;
    Configuration conf;
    Map<Integer, Cluster> clusters;
    Map<Integer, List<String>> clusterIdToPoints;


    private ClusterJob(String jobDir, Path input, Path clustersIn, Path output, Path dictionary) {
      this.jobDir = jobDir;
      this.input = input;
      this.clustersIn = clustersIn;
      this.output = output;
      this.dictionary = dictionary;
      clusters = new HashMap<Integer, Cluster>();
    }
  }

  private class ClusterCallable implements Callable<ClusterJob> {
    ClusterJob job;

    private ClusterCallable(ClusterJob job) {
      this.job = job;
      job.conf = new Configuration();
    }

    public ClusterJob call() throws Exception {

      KMeansDriver.run(job.conf, job.input, job.clustersIn, job.output, measure, convergence,
              maxIters, true, true);
      //job is done, should we build data structure now, in the background or wait until requested
      if (cacheClusters == false){
        job.clusters = loadClusters(job);
      }
      if (cachePoints == false){
        job.clusterIdToPoints = readPoints(new Path(job.jobDir + File.pathSeparator + "points"), job.conf);
      }
      return job;
    }
  }

  private static Map<Integer, Cluster> loadClusters(ClusterJob job) throws Exception {
    Map<Integer, Cluster> result = new HashMap<Integer, Cluster>();
    try {

      FileSystem fs = job.output.getFileSystem(job.conf);
      for (FileStatus seqFile : fs.globStatus(new Path(job.output, "part-*"))) {
        Path path = seqFile.getPath();
        //System.out.println("Input Path: " + path); doesn't this interfere with output?
        SequenceFile.Reader reader = new SequenceFile.Reader(fs, path, job.conf);
        try {
          Writable key = reader.getKeyClass().asSubclass(Writable.class).newInstance();
          Writable value = reader.getValueClass().asSubclass(Writable.class).newInstance();
          while (reader.next(key, value)) {
            Cluster cluster = (Cluster) value;
            result.put(cluster.getId(), cluster);
          }
        } finally {
          reader.close();
        }
      }
    } finally {

    }
    return result;
  }

  private static Map<Integer, List<String>> readPoints(Path pointsPathDir,
                                                                       Configuration conf) throws IOException {
    Map<Integer, List<String>> result = new TreeMap<Integer, List<String>>();

    FileSystem fs = pointsPathDir.getFileSystem(conf);
    FileStatus[] children = fs.listStatus(pointsPathDir, new PathFilter() {
      public boolean accept(Path path) {
        String name = path.getName();
        return !(name.endsWith(".crc") || name.startsWith("_"));
      }
    });

    for (FileStatus file : children) {
      Path path = file.getPath();
      SequenceFile.Reader reader = new SequenceFile.Reader(fs, path, conf);
      try {
        IntWritable key = reader.getKeyClass().asSubclass(IntWritable.class).newInstance();
        WeightedVectorWritable value = reader.getValueClass().asSubclass(WeightedVectorWritable.class).newInstance();
        while (reader.next(key, value)) {
          //key is the clusterId, value is a list of points
          //String clusterId = value.toString();
          List<String> pointList = result.get(key.get());
          if (pointList == null) {
            pointList = new ArrayList<String>();
            result.put(key.get(), pointList);
          }
          //We know we are dealing with named vectors, b/c we generated from the id field
          String name = ((NamedVector) value.getVector()).getName();
          pointList.add(name);
          //value = reader.getValueClass().asSubclass(WeightedVectorWritable.class).newInstance();
        }
      } catch (InstantiationException e) {
        log.error("Exception", e);
      } catch (IllegalAccessException e) {
        log.error("Exception", e);
      }
    }

    return result;
  }


  static class TermIndexWeight {
    private int index = -1;

    private final double weight;

    TermIndexWeight(int index, double weight) {
      this.index = index;
      this.weight = weight;
    }
  }

  //TODO: remove once MAHOUT-536 is committed
  public static String getTopFeatures(Vector vector, String[] dictionary, int numTerms) {

    List<TermIndexWeight> vectorTerms = new ArrayList<TermIndexWeight>();

    Iterator<Vector.Element> iter = vector.iterateNonZero();
    while (iter.hasNext()) {
      Vector.Element elt = iter.next();
      vectorTerms.add(new TermIndexWeight(elt.index(), elt.get()));
    }

    // Sort results in reverse order (ie weight in descending order)
    Collections.sort(vectorTerms, new Comparator<TermIndexWeight>() {

      public int compare(TermIndexWeight one, TermIndexWeight two) {
        return Double.compare(two.weight, one.weight);
      }
    });

    Collection<Pair<String, Double>> topTerms = new LinkedList<Pair<String, Double>>();

    for (int i = 0; (i < vectorTerms.size()) && (i < numTerms); i++) {
      int index = vectorTerms.get(i).index;
      String dictTerm = dictionary[index];
      if (dictTerm == null) {
        log.error("Dictionary entry missing for {}", index);
        continue;
      }
      topTerms.add(new Pair<String, Double>(dictTerm, vectorTerms.get(i).weight));
    }

    StringBuilder sb = new StringBuilder(100);

    for (Pair<String, Double> item : topTerms) {
      String term = item.getFirst();
      sb.append("\n\t\t");
      sb.append(StringUtils.rightPad(term, 40));
      sb.append("=>");
      sb.append(StringUtils.leftPad(item.getSecond().toString(), 20));
    }
    return sb.toString();
  }

  public void init(NamedList args) {
    //Defer all work to the clustering engine init
  }

  private static VectorWriter getSeqFileWriter(String outFile) throws IOException {
    Path path = new Path(outFile);
    Configuration conf = new Configuration();
    FileSystem fs = FileSystem.get(conf);
    // TODO: Make this parameter driven

    SequenceFile.Writer seqWriter = SequenceFile.createWriter(fs, conf, path, LongWritable.class,
      VectorWritable.class);

    return new SequenceFileVectorWriter(seqWriter);
  }


}
