package com.grantingersoll.intell.clustering;


/**
 *
 *
 **/
public interface KMeansClusteringParams {
  public static final String KMEANS = "kmeans";
  public static final String LIST_CLUSTERS = KMEANS + ".list";
  //given a doc id, what cluster(s) does it belong too
  public static final String IN_CLUSTER = KMEANS + ".in";
  //given a cluster id, list the points
  public static final String LIST_POINTS = KMEANS + ".list.points";
}
