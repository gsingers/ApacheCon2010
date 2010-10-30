package com.grantingersoll.intell.clustering;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.lucene.search.Searcher;
import org.apache.mahout.clustering.kmeans.KMeansDriver;
import org.apache.mahout.common.distance.CosineDistanceMeasure;
import org.apache.mahout.common.distance.DistanceMeasure;
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
import org.apache.solr.util.RefCounted;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
public class KMeansClusteringEngine extends DocumentClusteringEngine implements SolrEventListener {
  protected File clusterBaseDir;
  protected String inputField;
  protected SolrCore core;

  private DistanceMeasure measure;
  private double convergence = 0.001;
  private int maxIters = 20;

  //private Object swapContext = new Object();
  private ExecutorService execService;


  public KMeansClusteringEngine() {
    execService = Executors.newSingleThreadExecutor();
    measure = new CosineDistanceMeasure();
  }

  @Override
  public NamedList cluster(SolrParams solrParams) {
    NamedList result = null;
    return result;
  }

  @Override
  public NamedList cluster(DocSet docSet, SolrParams solrParams) {
    NamedList result = null;
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
      log.info("Dumping " + inputField + " to " + clusterBaseDir);
      String idName = keyField.getName();
      Weight weight = new TFIDF();
      SolrIndexReader reader = newSearcher.getReader();
      try {
        TermInfo termInfo = new CachedTermInfo(reader, "content", 1, 100);
        LuceneIterable li = new LuceneIterable(reader, idName, inputField, new TFDFMapper(reader, weight, termInfo));
        Date now = new Date();
        File outFile = new File(clusterBaseDir, "index-" + inputField + "-" + now.getTime() + ".vec");
        VectorWriter vectorWriter = getSeqFileWriter(outFile.getAbsolutePath());
        long numDocs = vectorWriter.write(li, Integer.MAX_VALUE);
        vectorWriter.close();
        log.info("Wrote: {} vectors", numDocs);
        File dictOutFile = new File(clusterBaseDir, "dict-" + inputField + "-" + now.getTime() + ".txt");
        log.info("Dictionary Output file: {}", dictOutFile);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(dictOutFile), Charset.forName("UTF8")));
        JWriterTermInfoWriter tiWriter = new JWriterTermInfoWriter(writer, "\t", inputField);
        tiWriter.write(termInfo);
        tiWriter.close();
        writer.close();
        //OK, the dictionary is dumped, now we can cluster, do this via a thread in the background.
        //when it's done, we can switch to it
        Future theFuture = execService.submit(new ClusterCallable(new Path(outFile.getAbsolutePath()),
                                  new Path(clusterBaseDir + File.pathSeparator + "clusters"),
                                  new Path(clusterBaseDir + File.pathSeparator + "output")));
      } catch (IOException e) {
        log.error("Exception", e);
      }
    }
  }

  private class ClusterCallable implements Callable {
    private Path input, clustersIn, output;

    private ClusterCallable(Path input, Path clustersIn, Path output) {
      this.input = input;
      this.clustersIn = clustersIn;
      this.output = output;
    }

    public Object call() throws Exception {
      Object result = null;
      KMeansDriver.run(new Configuration(), input, clustersIn, output, measure, convergence,
              maxIters, true, true);
      return result;
    }
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
