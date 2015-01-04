package com.twitter.scalding_internal.db.vertica;

import java.io.IOException;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.RecordReader;

import cascading.flow.FlowProcess;
import cascading.scheme.Scheme;
import cascading.tap.SinkMode;
import cascading.tap.hadoop.Hfs;
import cascading.tuple.TupleEntryIterator;
import cascading.util.Util;

public class VerticaSinkTap extends Hfs {
  private transient VerticaSinkCompletionHandler completionHandler;

  public VerticaSinkTap(Scheme<JobConf, RecordReader, OutputCollector, ?, ?> scheme,
                        JobConf conf, SinkMode sinkMode,
                        VerticaSinkCompletionHandler completionHandler) {
    super(scheme, initTemporaryPath(conf, true), sinkMode);
    this.completionHandler = completionHandler;
  }

  private static String initTemporaryPath(JobConf conf, boolean unique) {
    return new Path(getTempPath(conf),
      "Vrt" + Util.createUniqueID().substring(0, 5)).toString();
  }

  @Override
  public boolean commitResource(JobConf conf) throws IOException {
    boolean superResult = super.commitResource(conf);
    if (superResult) {
      return completionHandler.commitResource(conf, stringPath);
    } else {
      return superResult;
    }
  }

  public TupleEntryIterator openForRead(FlowProcess<JobConf> flowProcess,
                                        RecordReader input) throws IOException {
    throw new IOException("Reading not supported");
  }
}
