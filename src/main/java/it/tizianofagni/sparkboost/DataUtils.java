/*
 *
 * ****************
 * Copyright 2015 Tiziano Fagni (tiziano.fagni@isti.cnr.it)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ******************
 */

package it.tizianofagni.sparkboost;


import org.apache.commons.lang.ArrayUtils;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.mllib.linalg.SparseVector;
import org.apache.spark.mllib.linalg.Vectors;
import scala.Tuple2;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author Tiziano Fagni (tiziano.fagni@isti.cnr.it)
 */
public class DataUtils {

    /**
     * Load data file in LibSVm format. The documents IDs are assigned according to the row index in the original
     * file, i.e. useful at classification time. We are assuming that the feature IDs are the same as the training
     * file used to build the classification model.
     *
     * @param sc       The spark context.
     * @param dataFile The data file.
     * @return An RDD containing the read points.
     */
    public static JavaRDD<MultilabelPoint> loadLibSvmFileFormatDataAsList(JavaSparkContext sc, String dataFile, boolean labels0Based, boolean binaryProblem) {
        if (sc == null)
            throw new NullPointerException("The Spark Context is 'null'");
        if (dataFile == null || dataFile.isEmpty())
            throw new IllegalArgumentException("The dataFile is 'null'");

        JavaRDD<String> lines = sc.textFile(dataFile).cache();
        int numFeatures = computeNumFeatures(lines);

        ArrayList<MultilabelPoint> points = new ArrayList<>();
        try {
            BufferedReader br = new BufferedReader(new FileReader(dataFile));

            try {
                int docID = 0;
                String line = br.readLine();
                while (line != null) {
                    if (line.isEmpty())
                        return null;
                    String[] fields = line.split("\\s+");
                    String[] t = fields[0].split(",");
                    int[] labels = new int[0];
                    if (!binaryProblem) {
                        labels = new int[t.length];
                        for (int i = 0; i < t.length; i++) {
                            String label = t[i];
                            if (labels0Based)
                                labels[i] = new Double(Double.parseDouble(label)).intValue();
                            else
                                labels[i] = new Double(Double.parseDouble(label)).intValue() - 1;
                            if (labels[i] < 0)
                                throw new IllegalArgumentException("In current configuration I obtain a negative label ID value. Please check if this is a problem binary or multiclass " +
                                        "and if the labels IDs are in form 0-based or 1-based");
                        }
                    } else {
                        if (t.length > 1)
                            throw new IllegalArgumentException("In binary problem you can only specify one label ID (+1 or -1) per document as valid label IDs");
                        int label = new Double(Double.parseDouble(t[0])).intValue();
                        if (label > 0) {
                            labels = new int[]{0};
                        }
                    }
                    ArrayList<Integer> indexes = new ArrayList<Integer>();
                    ArrayList<Double> values = new ArrayList<Double>();
                    for (int j = 1; j < fields.length; j++) {
                        String data = fields[j];
                        if (data.startsWith("#"))
                            // Beginning of a comment. Skip it.
                            break;
                        String[] featInfo = data.split(":");
                        // Transform feature ID value in 0-based.
                        int featID = Integer.parseInt(featInfo[0]) - 1;
                        double value = Double.parseDouble(featInfo[1]);
                        indexes.add(featID);
                        values.add(value);
                    }

                    SparseVector v = (SparseVector) Vectors.sparse(numFeatures, indexes.stream().mapToInt(i -> i).toArray(), values.stream().mapToDouble(i -> i).toArray());
                    points.add(new MultilabelPoint(docID, v, labels));

                    line = br.readLine();
                    docID++;
                }
            } finally {
                br.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("Reading input LibSVM data file", e);
        }

        return sc.parallelize(points);
    }

    /**
     * Load data file in LibSVm format. The documents IDs are assigned arbitrarily by Spark.
     *
     * @param sc       The spark context.
     * @param dataFile The data file.
     * @return An RDD containing the read points.
     */
    public static JavaRDD<MultilabelPoint> loadLibSvmFileFormatData(JavaSparkContext sc, String dataFile, boolean labels0Based, boolean binaryProblem) {
        if (sc == null)
            throw new NullPointerException("The Spark Context is 'null'");
        if (dataFile == null || dataFile.isEmpty())
            throw new IllegalArgumentException("The dataFile is 'null'");
        JavaRDD<String> lines = sc.textFile(dataFile).cache();
        int localNumFeatures = computeNumFeatures(lines);
        Broadcast<Integer> distNumFeatures = sc.broadcast(localNumFeatures);
        JavaRDD<MultilabelPoint> docs = lines.filter(line -> !line.isEmpty()).zipWithIndex().map(item -> {
            int numFeatures = distNumFeatures.getValue();
            String line = item._1();
            long indexLong = item._2();
            int index = (int) indexLong;
            String[] fields = line.split("\\s+");
            String[] t = fields[0].split(",");
            int[] labels = new int[0];
            if (!binaryProblem) {
                labels = new int[t.length];
                for (int i = 0; i < t.length; i++) {
                    String label = t[i];
                    // Labels should be already 0-based.
                    if (labels0Based)
                        labels[i] = new Double(Double.parseDouble(label)).intValue();
                    else
                        labels[i] = new Double(Double.parseDouble(label)).intValue() - 1;
                    if (labels[i] < 0)
                        throw new IllegalArgumentException("In current configuration I obtain a negative label ID value. Please check if this is a problem binary or multiclass " +
                                "and if the labels IDs are in form 0-based or 1-based");
                    assert (labels[i] >= 0);
                }
            } else {
                if (t.length > 1)
                    throw new IllegalArgumentException("In binary problem you can only specify one label ID (+1 or -1) per document as valid label IDs");
                int label = new Double(Double.parseDouble(t[0])).intValue();
                if (label > 0) {
                    labels = new int[]{0};
                }
            }
            ArrayList<Integer> indexes = new ArrayList<Integer>();
            ArrayList<Double> values = new ArrayList<Double>();
            for (int j = 1; j < fields.length; j++) {
                String data = fields[j];
                if (data.startsWith("#"))
                    // Beginning of a comment. Skip it.
                    break;
                String[] featInfo = data.split(":");
                // Transform feature ID value in 0-based.
                int featID = Integer.parseInt(featInfo[0]) - 1;
                double value = Double.parseDouble(featInfo[1]);
                indexes.add(featID);
                values.add(value);
            }

            SparseVector v = (SparseVector) Vectors.sparse(numFeatures, indexes.stream().mapToInt(i -> i).toArray(), values.stream().mapToDouble(i -> i).toArray());
            return new MultilabelPoint(index, v, labels);
        });

        return docs;
    }

    protected static int computeNumFeatures(JavaRDD<String> lines) {
        int maxFeatureID = lines.map(line -> {
            if (line.isEmpty())
                return -1;
            String[] fields = line.split("\\s+");
            int maximumFeatID = 0;
            for (int j = 1; j < fields.length; j++) {
                String data = fields[j];
                if (data.startsWith("#"))
                    // Beginning of a comment. Skip it.
                    break;
                String[] featInfo = data.split(":");
                int featID = Integer.parseInt(featInfo[0]);
                maximumFeatID = Math.max(featID, maximumFeatID);
            }
            return maximumFeatID;
        }).reduce((val1, val2) -> val1 < val2 ? val2 : val1);

        return maxFeatureID;
    }

    public static int getNumDocuments(JavaRDD<MultilabelPoint> documents) {
        if (documents == null)
            throw new NullPointerException("The documents RDD is 'null'");
        return (int) documents.count();
    }

    public static int getNumLabels(JavaRDD<MultilabelPoint> documents) {
        if (documents == null)
            throw new NullPointerException("The documents RDD is 'null'");
        int maxValidLabelID = documents.map(doc -> {
            List<Integer> values = Arrays.asList(ArrayUtils.toObject(doc.getLabels()));
            if (values.size() == 0)
                return 0;
            else
                return Collections.max(values);
        }).reduce((m1, m2) -> Math.max(m1, m2));
        return maxValidLabelID + 1;
    }

    public static int getNumFeatures(JavaRDD<MultilabelPoint> documents) {
        if (documents == null)
            throw new NullPointerException("The documents RDD is 'null'");
        return documents.take(1).get(0).getFeatures().size();
    }

    public static JavaRDD<LabelDocuments> getLabelDocuments(JavaRDD<MultilabelPoint> documents) {
        return documents.flatMapToPair(doc -> {
            int[] labels = doc.getLabels();
            ArrayList<Integer> docAr = new ArrayList<Integer>();
            docAr.add(doc.getPointID());
            ArrayList<Tuple2<Integer, ArrayList<Integer>>> ret = new ArrayList<Tuple2<Integer, ArrayList<Integer>>>();
            for (int i = 0; i < labels.length; i++) {
                ret.add(new Tuple2<>(labels[i], docAr));
            }
            return ret;
        }).reduceByKey((list1, list2) -> {
            ArrayList<Integer> ret = new ArrayList<Integer>();
            ret.addAll(list1);
            ret.addAll(list2);
            Collections.sort(ret);
            return ret;
        }).map(item -> {
            return new LabelDocuments(item._1(), item._2().stream().mapToInt(i -> i).toArray());
        });
    }

    public static JavaRDD<FeatureDocuments> getFeatureDocuments(JavaRDD<MultilabelPoint> documents) {
        return documents.flatMapToPair(doc -> {
            SparseVector feats = doc.getFeatures();
            int[] indices = feats.indices();
            ArrayList<Tuple2<Integer, FeatureDocuments>> ret = new ArrayList<>();
            for (int i = 0; i < indices.length; i++) {
                int featureID = indices[i];
                int[] docs = new int[]{doc.getPointID()};
                int[][] labels = new int[1][];
                labels[0] = doc.getLabels();
                ret.add(new Tuple2<>(featureID, new FeatureDocuments(featureID, docs, labels)));
            }
            return ret;
        }).reduceByKey((f1, f2) -> {
            int numDocs = f1.getDocuments().length + f2.getDocuments().length;
            int[] docsMerged = new int[numDocs];
            int[][] labelsMerged = new int[numDocs][];
            // Add first feature info.
            for (int idx = 0; idx < f1.getDocuments().length; idx++) {
                docsMerged[idx] = f1.getDocuments()[idx];
            }
            for (int idx = 0; idx < f1.getDocuments().length; idx++) {
                labelsMerged[idx] = f1.getLabels()[idx];
            }

            // Add second feature info.
            for (int idx = f1.getDocuments().length; idx < numDocs; idx++) {
                docsMerged[idx] = f2.getDocuments()[idx - f1.getDocuments().length];
            }
            for (int idx = f1.getDocuments().length; idx < numDocs; idx++) {
                labelsMerged[idx] = f2.getLabels()[idx - f1.getDocuments().length];
            }
            return new FeatureDocuments(f1.featureID, docsMerged, labelsMerged);
        }).map(item -> item._2());
    }

    /**
     * Save a boosting classifier model to the specified output model path (any valid path recognized by
     * Spark/Hadoop).
     * <br/><br/>
     * IMPORTANT NOTE: if you are executing Spark in local mode under Windows, you can get this strange error
     * as described <a href="https://issues.apache.org/jira/browse/SPARK-6961?jql=project%20%3D%20SPARK%20AND%20text%20~%20%22save%20file%20local%22">here</a>.
     * Currently the workaround is to install the winutils executable on the path corresponding to Hadoop installation (see
     * <a href="http://stackoverflow.com/questions/24832284/nullpointerexception-in-spark-sql">here</a> for more details about this workaround).
     *
     * @param sc              The Spark context.
     * @param classifier      The classifier to be save.
     * @param outputModelPath The output path where to save the model.
     */
    public static void saveModel(JavaSparkContext sc, BoostClassifier classifier, String outputModelPath) {
        if (sc == null)
            throw new NullPointerException("The Spark context is 'null'");
        if (classifier == null)
            throw new NullPointerException("The classifier is 'null'");
        if (outputModelPath == null)
            throw new NullPointerException("The output model path is 'null'");

        ArrayList<BoostClassifier> clList = new ArrayList<>();
        clList.add(classifier);
        JavaRDD<BoostClassifier> rdd = sc.parallelize(clList);
        rdd.saveAsObjectFile(outputModelPath);
    }

    /**
     * Load a boosting classifier model  from the specified input model path (any valid path recognized by
     * Spark/Hadoop).
     *
     * @param sc             The Spark context.
     * @param inputModelPath The input model path.
     * @return The corresponding boosting classifier.
     */
    public static BoostClassifier loadModel(JavaSparkContext sc, String inputModelPath) {
        if (sc == null)
            throw new NullPointerException("The Spark context is 'null'");
        if (inputModelPath == null)
            throw new NullPointerException("The input model path is 'null'");

        BoostClassifier cl = (BoostClassifier) sc.objectFile(inputModelPath).take(1).get(0);
        return cl;
    }

    public static class LabelDocuments implements Serializable {
        private final int labelID;
        private final int[] documents;

        public LabelDocuments(int labelID, int[] documents) {
            this.labelID = labelID;
            this.documents = documents;
        }

        public int getLabelID() {
            return labelID;
        }

        public int[] getDocuments() {
            return documents;
        }
    }

    public static class FeatureDocuments implements Serializable {
        private final int featureID;
        private final int[] documents;
        private final int[][] labels;

        public FeatureDocuments(int featureID, int[] documents, int[][] labels) {
            this.featureID = featureID;
            this.documents = documents;
            this.labels = labels;
        }

        public int getFeatureID() {
            return featureID;
        }

        public int[] getDocuments() {
            return documents;
        }

        public int[][] getLabels() {
            return labels;
        }
    }
}