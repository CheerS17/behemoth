package com.digitalpebble.behemoth.io.warc;

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

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.nutch.metadata.HttpHeaders;
import org.apache.nutch.protocol.ProtocolException;

import com.digitalpebble.behemoth.BehemothConfiguration;
import com.digitalpebble.behemoth.BehemothDocument;
import com.digitalpebble.behemoth.DocumentFilter;

import edu.cmu.lemurproject.WarcFileInputFormat;
import edu.cmu.lemurproject.WarcRecord;
import edu.cmu.lemurproject.WritableWarcRecord;

/**
 * Converts a WARC archive into a Behemoth datastructure for further processing
 */
public class WARCConverterJob extends Configured implements Tool,
		Mapper<LongWritable, WritableWarcRecord, Text, BehemothDocument> {

	public static final Logger LOG = LoggerFactory
			.getLogger(WARCConverterJob.class);

	private Text newKey = new Text();

	private DocumentFilter filter;

	public WARCConverterJob() {
		this(null);
	}

	public WARCConverterJob(Configuration conf) {
		super(conf);
	}

	public void configure(JobConf job) {
		setConf(job);
		filter = DocumentFilter.getFilters(job);
	}

	public void close() {
	}

	public void map(LongWritable key, WritableWarcRecord record,
			OutputCollector<Text, BehemothDocument> output, Reporter reporter)
			throws IOException {

		WarcRecord wr = record.getRecord();

		if (wr.getHeaderRecordType().equals("response") == false)
			return;

		byte[] binarycontent = wr.getContent();

		String uri = wr.getHeaderMetadataItem("WARC-Target-URI");

		// skip non http documents
		if (uri.startsWith("http") == false)
			return;

		String ip = wr.getHeaderMetadataItem("WARC-IP-Address");

		HttpResponse response;
		try {
			response = new HttpResponse(binarycontent);
		} catch (ProtocolException e) {
			return;
		}

		BehemothDocument behemothDocument = new BehemothDocument();

		behemothDocument.setUrl(uri);
		newKey.set(uri);

		String contentType = response.getHeader(HttpHeaders.CONTENT_TYPE);
		behemothDocument.setContentType(contentType);
		behemothDocument.setContent(response.getContent());

		MapWritable md = behemothDocument.getMetadata(true);

		// add the metadata
		for (String mdkey : response.getHeaders().names()) {
			String value = response.getHeaders().get(mdkey);
			md.put(new Text(mdkey), new Text(value));
		}

		// store the IP address as metadata
		if (StringUtils.isNotBlank(ip))
			md.put(new Text("IP"), new Text(ip));

		if (filter.keep(behemothDocument)) {
			output.collect(newKey, behemothDocument);
			reporter.getCounter("WARCCONVERTER", "KEPT").increment(1l);
		} else
			reporter.getCounter("WARCCONVERTER", "FILTERED").increment(1l);

	}

	public void convert(Path warcpath, Path output) throws IOException {

		JobConf job = new JobConf(getConf());
		job.setJobName("Convert WARC " + warcpath);

		job.setJarByClass(this.getClass());

		FileInputFormat.addInputPath(job, warcpath);
		job.setInputFormat(WarcFileInputFormat.class);

		job.setMapperClass(WARCConverterJob.class);

		// no reducers
		job.setNumReduceTasks(0);

		FileOutputFormat.setOutputPath(job, output);
		job.setOutputFormat(SequenceFileOutputFormat.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(BehemothDocument.class);

		long start = System.currentTimeMillis();
		JobClient.runJob(job);
		long finish = System.currentTimeMillis();
		if (LOG.isInfoEnabled()) {
			LOG.info("WARCConverterJob completed. Timing: " + (finish - start)
					+ " ms");
		}
	}

	public static void main(String[] args) throws Exception {
		int res = ToolRunner.run(BehemothConfiguration.create(),
				new WARCConverterJob(), args);
		System.exit(res);
	}

	public int run(String[] args) throws Exception {
		String usage = "Usage: WARCConverterJob archive output";

		if (args.length == 0) {
			System.err.println(usage);
			System.exit(-1);
		}
		Path segment = new Path(args[0]);
		Path output = new Path(args[1]);
		convert(segment, output);
		return 0;
	}

}
