package net.sf.cram;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import net.sf.cram.CramTools.LevelConverter;
import net.sf.cram.build.CramIO;
import net.sf.cram.encoding.DataReaderFactory;
import net.sf.cram.encoding.ReaderToFastQ;
import net.sf.cram.io.DefaultBitInputStream;
import net.sf.cram.ref.ReferenceSource;
import net.sf.cram.structure.Container;
import net.sf.cram.structure.CramHeader;
import net.sf.cram.structure.Slice;
import net.sf.picard.util.Log;
import net.sf.picard.util.Log.LogLevel;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMSequenceRecord;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.FileConverter;

public class Cram2Fastq {
	private static Log log = Log.getInstance(Cram2Fastq.class);

	private static void printUsage(JCommander jc) {
		StringBuilder sb = new StringBuilder();
		sb.append("\n");
		jc.usage(sb);

		System.out.println("Version "
				+ Cram2Fastq.class.getPackage().getImplementationVersion());
		System.out.println(sb.toString());
	}

	public static void main(String[] args) throws IOException,
			IllegalArgumentException, IllegalAccessException {
		Params params = new Params();
		JCommander jc = new JCommander(params);
		try {
			jc.parse(args);
		} catch (Exception e) {
			System.out
					.println("Failed to parse parameteres, detailed message below: ");
			System.out.println(e.getMessage());
			System.out.println();
			System.out.println("See usage: -h");
			System.exit(1);
		}

		if (args.length == 0 || params.help) {
			printUsage(jc);
			System.exit(1);
		}

		Log.setGlobalLogLevel(params.logLevel);

		if (params.reference == null)
			log.warn("No reference file specified, remote access over internet may be used to download public sequences. ");
		ReferenceSource referenceSource = new ReferenceSource(params.reference);

		// OutputStream os = (new BufferedOutputStream(new FileOutputStream(
		// fqgzFile)));

		byte[] ref = null;

		InputStream is = new FileInputStream(params.cramFile);

		CramHeader cramHeader = CramIO.readCramHeader(is);
		Container container = null;
		ReaderToFastQ reader = new ReaderToFastQ();
		while ((container = CramIO.readContainer(is)) != null) {
			DataReaderFactory f = new DataReaderFactory();

			for (Slice s : container.slices) {
				if (s.sequenceId != SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
					SAMSequenceRecord sequence = cramHeader.samFileHeader
							.getSequence(s.sequenceId);
					ref = referenceSource.getReferenceBases(sequence, true);
				}
				Map<Integer, InputStream> inputMap = new HashMap<Integer, InputStream>();
				for (Integer exId : s.external.keySet()) {
					inputMap.put(exId,
							new ByteArrayInputStream(s.external.get(exId)
									.getRawContent()));
				}

				reader.ref = ref;
				reader.prevAlStart = s.alignmentStart;
				reader.substitutionMatrix = container.h.substitutionMatrix;
				reader.recordCounter = 0;
				f.buildReader(reader, new DefaultBitInputStream(
						new ByteArrayInputStream(s.coreBlock.getRawContent())),
						inputMap, container.h, s.sequenceId);

				for (int i = 0; i < s.nofRecords; i++) {
					reader.read();
				}
				reader.buf.flip();
				long sum = 0;
				for (int i = 0; i < reader.buf.limit(); i++)
					sum += reader.buf.get(i);
				System.out.println(sum);
				// os.write(reader.buf.array(), 0, reader.buf.limit());
				reader.buf.clear();
			}
		}
		// os.close();
	}

	@Parameters(commandDescription = "CRAM to BAM conversion. ")
	static class Params {
		@Parameter(names = { "-l", "--log-level" }, description = "Change log level: DEBUG, INFO, WARNING, ERROR.", converter = LevelConverter.class)
		LogLevel logLevel = LogLevel.ERROR;

		@Parameter(names = { "-h", "--help" }, description = "Print help and quit")
		boolean help = false;

		@Parameter(names = { "--input-cram-file", "-I" }, converter = FileConverter.class, description = "The path to the CRAM file to uncompress. Omit if standard input (pipe).")
		File cramFile;

		@Parameter(names = { "--reference-fasta-file", "-R" }, converter = FileConverter.class, description = "Path to the reference fasta file, it must be uncompressed and indexed (use 'samtools faidx' for example). ")
		File reference;

		@Parameter(names = { "--output-fastq-file", "-O" }, converter = FileConverter.class, description = "The path to the output fastq file.")
		File outputFile;
	}

}
