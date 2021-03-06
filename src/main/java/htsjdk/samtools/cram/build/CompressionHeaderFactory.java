/**
 * ****************************************************************************
 * Copyright 2013 EMBL-EBI
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ****************************************************************************
 */
package htsjdk.samtools.cram.build;

import htsjdk.samtools.cram.common.MutableInt;
import htsjdk.samtools.cram.encoding.ByteArrayLenEncoding;
import htsjdk.samtools.cram.encoding.ByteArrayStopEncoding;
import htsjdk.samtools.cram.encoding.ExternalByteEncoding;
import htsjdk.samtools.cram.encoding.ExternalCompressor;
import htsjdk.samtools.cram.encoding.ExternalIntegerEncoding;
import htsjdk.samtools.cram.encoding.NullEncoding;
import htsjdk.samtools.cram.encoding.huffman.codec.HuffmanIntegerEncoding;
import htsjdk.samtools.cram.encoding.rans.RANS;
import htsjdk.samtools.cram.encoding.readfeatures.ReadFeature;
import htsjdk.samtools.cram.encoding.readfeatures.Substitution;
import htsjdk.samtools.cram.structure.CompressionHeader;
import htsjdk.samtools.cram.structure.CramCompressionRecord;
import htsjdk.samtools.cram.structure.EncodingKey;
import htsjdk.samtools.cram.structure.EncodingParams;
import htsjdk.samtools.cram.structure.ReadTag;
import htsjdk.samtools.cram.structure.SubstitutionMatrix;
import htsjdk.samtools.util.RuntimeIOException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Implementation of {@link htsjdk.samtools.cram.build.CramCompression} that
 * mostly relies on GZIP and RANS.
 */
public class CompressionHeaderFactory {
	private final Map<Integer, EncodingDetails> bestEncodings = new HashMap<Integer, EncodingDetails>();
	private final ByteArrayOutputStream baosForTagValues;

	public CompressionHeaderFactory() {
		baosForTagValues = new ByteArrayOutputStream(1024 * 1024);
	}

	/**
	 * Decides on compression methods to use for the given records.
	 *
	 * @param records
	 *            the data to be compressed
	 * @param substitutionMatrix
	 *            a matrix of base substitution frequencies, can be null, in
	 *            which case it is re-calculated.
	 * @param sorted
	 *            if true the records are assumed to be sorted by alignment
	 *            position
	 * @return {@link htsjdk.samtools.cram.structure.CompressionHeader} object
	 *         describing the encoding chosen for the data
	 */
	public CompressionHeader build(final List<CramCompressionRecord> records, SubstitutionMatrix substitutionMatrix,
			final boolean sorted) {

		final CompressionHeaderBuilder builder = new CompressionHeaderBuilder(sorted);

		builder.addExternalByteRansOrderOneEncoding(EncodingKey.BA_Base);
		builder.addExternalByteRansOrderOneEncoding(EncodingKey.QS_QualityScore);
		builder.addExternalByteArrayStopTabGzipEncoding(EncodingKey.RN_ReadName);
		builder.addExternalIntegerRansOrderOneEncoding(EncodingKey.BF_BitFlags);
		builder.addExternalIntegerRansOrderOneEncoding(EncodingKey.CF_CompressionBitFlags);
		builder.addExternalIntegerRansOrderZeroEncoding(EncodingKey.RI_RefId);
		builder.addExternalIntegerRansOrderOneEncoding(EncodingKey.RL_ReadLength);

		builder.addExternalIntegerRansOrderZeroEncoding(EncodingKey.AP_AlignmentPositionOffset);
		builder.addExternalIntegerRansOrderOneEncoding(EncodingKey.RG_ReadGroup);
		builder.addExternalIntegerRansOrderOneEncoding(EncodingKey.NF_RecordsToNextFragment);
		builder.addExternalIntegerGzipEncoding(EncodingKey.TC_TagCount);
		builder.addExternalIntegerGzipEncoding(EncodingKey.TN_TagNameAndType);

		builder.addExternalIntegerGzipEncoding(EncodingKey.FN_NumberOfReadFeatures);
		builder.addExternalIntegerGzipEncoding(EncodingKey.FP_FeaturePosition);
		builder.addExternalByteGzipEncoding(EncodingKey.FC_FeatureCode);
		builder.addExternalByteGzipEncoding(EncodingKey.BS_BaseSubstitutionCode);

		builder.addExternalByteArrayStopTabGzipEncoding(EncodingKey.IN_Insertion);
		builder.addExternalByteArrayStopTabGzipEncoding(EncodingKey.SC_SoftClip);

		builder.addExternalIntegerGzipEncoding(EncodingKey.DL_DeletionLength);
		builder.addExternalIntegerGzipEncoding(EncodingKey.HC_HardClip);
		builder.addExternalIntegerGzipEncoding(EncodingKey.PD_padding);
		builder.addExternalIntegerGzipEncoding(EncodingKey.RS_RefSkip);
		builder.addExternalIntegerGzipEncoding(EncodingKey.MQ_MappingQualityScore);
		builder.addExternalIntegerGzipEncoding(EncodingKey.MF_MateBitFlags);
		builder.addExternalIntegerRansOrderOneEncoding(EncodingKey.NS_NextFragmentReferenceSequenceID);
		builder.addExternalIntegerGzipEncoding(EncodingKey.NP_NextFragmentAlignmentStart);
		builder.addExternalIntegerRansOrderOneEncoding(EncodingKey.TS_InsetSize);

		builder.setTagIdDictionary(buildTagIdDictionary(records));
		builder.addExternalIntegerEncoding(EncodingKey.TL_TagIdList, ExternalCompressor.createGZIP());

		buildTagEncodings(records, builder);

		if (substitutionMatrix == null) {
			substitutionMatrix = new SubstitutionMatrix(buildFrequencies(records));
			updateSubstitutionCodes(records, substitutionMatrix);
		}
		builder.setSubstitutionMatrix(substitutionMatrix);
		return builder.getHeader();
	}

	/**
	 * Iterate over the records and for each tag found come up with an encoding.
	 * Tag encodings are registered via the builder.
	 *
	 * @param records
	 *            CRAM records holding the tags to be encoded
	 * @param builder
	 *            compression header builder to register encodings
	 */
	private void buildTagEncodings(final List<CramCompressionRecord> records, final CompressionHeaderBuilder builder) {
		final Set<Integer> tagIdSet = new HashSet<Integer>();

		for (final CramCompressionRecord record : records) {
			if (record.tags == null || record.tags.length == 0) {
				continue;
			}

			for (final ReadTag tag : record.tags) {
				tagIdSet.add(tag.keyType3BytesAsInt);
			}
		}

		for (final int tagId : tagIdSet) {
			if (bestEncodings.containsKey(tagId)) {
				builder.addTagEncoding(tagId, bestEncodings.get(tagId));
			} else {
				final EncodingDetails e = buildEncodingForTag(records, tagId);
				builder.addTagEncoding(tagId, e);
				bestEncodings.put(tagId, e);
			}
		}
	}

	/**
	 * Given the records update the substitution matrix with actual substitution
	 * codes.
	 *
	 * @param records
	 *            CRAM records
	 * @param substitutionMatrix
	 *            the matrix to be updated
	 */
	private static void updateSubstitutionCodes(final List<CramCompressionRecord> records,
			final SubstitutionMatrix substitutionMatrix) {
		for (final CramCompressionRecord record : records) {
			if (record.readFeatures != null) {
				for (final ReadFeature recordFeature : record.readFeatures) {
					if (recordFeature.getOperator() == Substitution.operator) {
						final Substitution substitution = ((Substitution) recordFeature);
						if (substitution.getCode() == -1) {
							final byte refBase = substitution.getReferenceBase();
							final byte base = substitution.getBase();
							substitution.setCode(substitutionMatrix.code(refBase, base));
						}
					}
				}
			}
		}
	}

	/**
	 * Build an array of substitution frequencies for the given CRAM records.
	 *
	 * @param records
	 *            CRAM records to scan
	 * @return a 2D array of frequencies, see
	 *         {@link htsjdk.samtools.cram.structure.SubstitutionMatrix}
	 */
	private static long[][] buildFrequencies(final List<CramCompressionRecord> records) {
		final long[][] frequencies = new long[200][200];
		for (final CramCompressionRecord record : records) {
			if (record.readFeatures != null) {
				for (final ReadFeature readFeature : record.readFeatures) {
					if (readFeature.getOperator() == Substitution.operator) {
						final Substitution substitution = ((Substitution) readFeature);
						final byte refBase = substitution.getReferenceBase();
						final byte base = substitution.getBase();
						frequencies[refBase][base]++;
					}
				}
			}
		}
		return frequencies;
	}

	/**
	 * Build a dictionary of tag ids.
	 *
	 * @param records
	 *            records holding the tags
	 * @return a 3D byte array: a set of unique lists of tag ids.
	 */
	private static byte[][][] buildTagIdDictionary(final List<CramCompressionRecord> records) {
		final Comparator<ReadTag> comparator = new Comparator<ReadTag>() {

			@Override
			public int compare(final ReadTag o1, final ReadTag o2) {
				return o1.keyType3BytesAsInt - o2.keyType3BytesAsInt;
			}
		};

		final Comparator<byte[]> baComparator = new Comparator<byte[]>() {

			@Override
			public int compare(final byte[] o1, final byte[] o2) {
				if (o1.length - o2.length != 0) {
					return o1.length - o2.length;
				}

				for (int i = 0; i < o1.length; i++) {
					if (o1[i] != o2[i]) {
						return o1[i] - o2[i];
					}
				}

				return 0;
			}
		};

		final Map<byte[], MutableInt> map = new TreeMap<byte[], MutableInt>(baComparator);
		final MutableInt noTagCounter = new MutableInt();
		map.put(new byte[0], noTagCounter);
		for (final CramCompressionRecord record : records) {
			if (record.tags == null) {
				noTagCounter.value++;
				record.tagIdsIndex = noTagCounter;
				continue;
			}

			Arrays.sort(record.tags, comparator);
			record.tagIds = new byte[record.tags.length * 3];

			int tagIndex = 0;
			for (int i = 0; i < record.tags.length; i++) {
				record.tagIds[i * 3] = (byte) record.tags[tagIndex].keyType3Bytes.charAt(0);
				record.tagIds[i * 3 + 1] = (byte) record.tags[tagIndex].keyType3Bytes.charAt(1);
				record.tagIds[i * 3 + 2] = (byte) record.tags[tagIndex].keyType3Bytes.charAt(2);
				tagIndex++;
			}

			MutableInt count = map.get(record.tagIds);
			if (count == null) {
				count = new MutableInt();
				map.put(record.tagIds, count);
			}
			count.value++;
			record.tagIdsIndex = count;
		}

		final byte[][][] dictionary = new byte[map.size()][][];
		int i = 0;
		for (final byte[] idsAsBytes : map.keySet()) {
			final int nofIds = idsAsBytes.length / 3;
			dictionary[i] = new byte[nofIds][];
			for (int j = 0; j < idsAsBytes.length;) {
				final int idIndex = j / 3;
				dictionary[i][idIndex] = new byte[3];
				dictionary[i][idIndex][0] = idsAsBytes[j++];
				dictionary[i][idIndex][1] = idsAsBytes[j++];
				dictionary[i][idIndex][2] = idsAsBytes[j++];
			}
			map.get(idsAsBytes).value = i++;
		}
		return dictionary;
	}

	/**
	 * Tag id is and integer where the first byte is its type and the other 2
	 * bytes represent the name. For example 'OQZ', where 'OQ' stands for
	 * original quality score tag and 'Z' stands for string type.
	 *
	 * @param tagID
	 *            a 3 byte tag id stored in an int
	 * @return tag type, the lowest byte in the tag id
	 */
	private static byte getTagType(final int tagID) {
		return (byte) (tagID & 0xFF);
	}

	private static boolean isFixedLengthTagValue(final byte type) {
		switch (type) {
		case 'A':
		case 'I':
		case 'i':
		case 's':
		case 'S':
		case 'c':
		case 'C':
		case 'f':
			return true;

		default:
			return false;
		}
	}

	private static ExternalCompressor getBestExternalCompressor(byte[] data) {
		final ExternalCompressor gzip = ExternalCompressor.createGZIP();
		final int gzipLen = gzip.compress(data).length;

		final ExternalCompressor rans0 = ExternalCompressor.createRANS(RANS.ORDER.ZERO);
		final int rans0Len = rans0.compress(data).length;

		final ExternalCompressor rans1 = ExternalCompressor.createRANS(RANS.ORDER.ONE);
		final int rans1Len = rans1.compress(data).length;

		final EncodingDetails d = new EncodingDetails();

		// find the best of general purpose codecs:
		final int minLen = Math.min(gzipLen, Math.min(rans0Len, rans1Len));
		if (minLen == rans0Len) {
			return rans0;
		} else if (minLen == rans1Len) {
			return rans1;
		} else {
			return gzip;
		}
	}

	private byte[] getDataForTag(final List<CramCompressionRecord> records, final int tagID) {
		baosForTagValues.reset();

		for (final CramCompressionRecord record : records) {
			if (record.tags == null) {
				continue;
			}

			for (final ReadTag tag : record.tags) {
				if (tag.keyType3BytesAsInt != tagID) {
					continue;
				}
				final byte[] valueBytes = tag.getValueAsByteArray();
				try {
					baosForTagValues.write(valueBytes);
				} catch (final IOException e) {
					throw new RuntimeIOException(e);
				}
			}
		}

		return baosForTagValues.toByteArray();
	}

	private static ByteSizeStats getMinByteSizeOfTagValues(final List<CramCompressionRecord> records, final int tagID) {
		final byte type = getTagType(tagID);
		ByteSizeStats stats = new ByteSizeStats();
		for (final CramCompressionRecord record : records) {
			if (record.tags == null) {
				continue;
			}

			for (final ReadTag tag : record.tags) {
				if (tag.keyType3BytesAsInt != tagID) {
					continue;
				}
				int size = net.sf.cram.common.Utils.getTagValueByteSize(type, tag.getValue());
				if (stats.min < size)
					stats.min = size;
				if (stats.max > size)
					stats.max = size;
			}
		}
		return stats;
	}

	private static int getUnusedByte(final byte[] data) {
		byte[] usage = new byte[256];
		for (byte b : data) {
			usage[0xFF & b] = 1;
		}

		for (int i = 0; i < 256; i++) {
			if (usage[i] == 0)
				return i;
		}
		return -1;
	}

	private static class ByteSizeStats {
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
	}

	/**
	 * Build an encoding for a specific tag for given records.
	 *
	 * @param records
	 *            CRAM records holding the tags
	 * @param tagID
	 *            an integer id of the tag
	 * @return an encoding for the tag
	 */
	private EncodingDetails buildEncodingForTag(final List<CramCompressionRecord> records, final int tagID) {
		EncodingDetails details = new EncodingDetails();
		final byte[] data = getDataForTag(records, tagID);

		details.compressor = getBestExternalCompressor(data);

		final byte type = getTagType(tagID);
		switch (type) {
		case 'A':
		case 'c':
		case 'C':
			details.params = ByteArrayLenEncoding.toParam(
					HuffmanIntegerEncoding.toParam(new int[] { 1 }, new int[] { 0 }),
					ExternalByteEncoding.toParam(tagID));
			return details;
		case 'I':
		case 'i':
		case 'f':
			details.params = ByteArrayLenEncoding.toParam(
					HuffmanIntegerEncoding.toParam(new int[] { 4 }, new int[] { 0 }),
					ExternalByteEncoding.toParam(tagID));
			return details;

		case 's':
		case 'S':
			details.params = ByteArrayLenEncoding.toParam(
					HuffmanIntegerEncoding.toParam(new int[] { 2 }, new int[] { 0 }),
					ExternalByteEncoding.toParam(tagID));
			return details;
		case 'Z':
		case 'B':
			ByteSizeStats stats = getMinByteSizeOfTagValues(records, tagID);
			boolean singleSize = stats.min == stats.max;
			if (singleSize) {
				details.params = ByteArrayLenEncoding.toParam(
						HuffmanIntegerEncoding.toParam(new int[] { stats.min }, new int[] { 0 }),
						ExternalByteEncoding.toParam(tagID));
				return details;
			}

			if (type == 'Z') {
				details.params = ByteArrayStopEncoding.toParam((byte) '\t', tagID);
				return details;
			}

			final int minSize_threshold_ForByteArrayStopEncoding = 100;
			if (stats.min > minSize_threshold_ForByteArrayStopEncoding) {
				int unusedByte = getUnusedByte(data);
				if (unusedByte > -1) {
					details.params = ByteArrayStopEncoding.toParam((byte) unusedByte, tagID);
					return details;
				}
			}

			details.params = ByteArrayLenEncoding.toParam(ExternalIntegerEncoding.toParam(tagID),
					ExternalByteEncoding.toParam(tagID));
			return details;
		default:
			throw new IllegalArgumentException("Unkown tag type: " + (char) type);
		}
	}

	/**
	 * A combination of external compressor and encoding params. This is all
	 * that is needed to encode a data series.
	 */
	private static class EncodingDetails {
		ExternalCompressor compressor;
		EncodingParams params;
	}

	/**
	 * A helper class to build
	 * {@link htsjdk.samtools.cram.structure.CompressionHeader} object.
	 */
	private static class CompressionHeaderBuilder {
		private final CompressionHeader header;
		private int externalBlockCounter;

		CompressionHeaderBuilder(final boolean sorted) {
			header = new CompressionHeader();
			header.externalIds = new ArrayList<Integer>();
			header.tMap = new TreeMap<Integer, EncodingParams>();

			header.encodingMap = new TreeMap<EncodingKey, EncodingParams>();
			for (final EncodingKey key : EncodingKey.values()) {
				header.encodingMap.put(key, NullEncoding.toParam());
			}

			externalBlockCounter = 0;
			header.APDelta = sorted;
		}

		CompressionHeader getHeader() {
			return header;
		}

		void addExternalEncoding(final EncodingKey encodingKey, final EncodingParams params,
				final ExternalCompressor compressor) {
			header.externalIds.add(externalBlockCounter);
			header.externalCompressors.put(externalBlockCounter, compressor);
			header.encodingMap.put(encodingKey, params);
			externalBlockCounter++;
		}

		void addExternalByteArrayStopTabGzipEncoding(final EncodingKey encodingKey) {
			addExternalEncoding(encodingKey, ByteArrayStopEncoding.toParam((byte) '\t', externalBlockCounter),
					ExternalCompressor.createGZIP());
		}

		void addExternalIntegerEncoding(final EncodingKey encodingKey, final ExternalCompressor compressor) {
			addExternalEncoding(encodingKey, ExternalIntegerEncoding.toParam(externalBlockCounter), compressor);
		}

		void addExternalIntegerGzipEncoding(final EncodingKey encodingKey) {
			addExternalEncoding(encodingKey, ExternalIntegerEncoding.toParam(externalBlockCounter),
					ExternalCompressor.createGZIP());
		}

		void addExternalByteEncoding(final EncodingKey encodingKey, final ExternalCompressor compressor) {
			addExternalEncoding(encodingKey, ExternalByteEncoding.toParam(externalBlockCounter), compressor);
		}

		void addExternalByteGzipEncoding(final EncodingKey encodingKey) {
			addExternalEncoding(encodingKey, ExternalByteEncoding.toParam(externalBlockCounter),
					ExternalCompressor.createGZIP());
		}

		void addExternalByteRansOrderOneEncoding(final EncodingKey encodingKey) {
			addExternalEncoding(encodingKey, ExternalByteEncoding.toParam(externalBlockCounter),
					ExternalCompressor.createRANS(RANS.ORDER.ONE));
		}

		void addExternalIntegerRansOrderOneEncoding(final EncodingKey encodingKey) {
			addExternalIntegerEncoding(encodingKey, ExternalCompressor.createRANS(RANS.ORDER.ONE));
		}

		void addExternalIntegerRansOrderZeroEncoding(final EncodingKey encodingKey) {
			addExternalIntegerEncoding(encodingKey, ExternalCompressor.createRANS(RANS.ORDER.ZERO));
		}

		void addTagEncoding(final int tagId, final EncodingDetails encodingDetails) {
			header.externalIds.add(tagId);
			header.externalCompressors.put(tagId, encodingDetails.compressor);
			header.tMap.put(tagId, encodingDetails.params);
		}

		void setTagIdDictionary(final byte[][][] dictionary) {
			header.dictionary = dictionary;
		}

		void setSubstitutionMatrix(final SubstitutionMatrix substitutionMatrix) {
			header.substitutionMatrix = substitutionMatrix;
		}
	}
}
