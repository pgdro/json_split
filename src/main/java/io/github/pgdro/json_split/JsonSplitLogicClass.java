/* Copyright 2026 Daniel-Gheorghe Popiniuc */
package io.github.pgdro.json_split;

import io.github.pgdro.tools.core.LogExposureClass;
import io.github.pgdro.tools.core.file.FileOperationsClass;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Properties;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

/**
 * JSON split logic
 */
public final class JsonSplitLogicClass {
    /** Minimum value length for bucketing */
    /* default */ private static final int MIN_BUCKET_LENGTH = 4;
    /** Length for bucketing customizable */
    private static int intBucketLength;
    /** variable for relevant field */
    private static String strRelevantField;
    /** variable for input JSON file */
    private static String strInputJsonFile;
    /** variable for destination folder */
    private static String strOutFolder;
    /** Writer for JSON content */
    private static Writer writer;

    /**
     * Buckets values
     * @param inOriginalValue given original value
     * @return String
     */
    private static String bucketFieldValue(final String inOriginalValue) {
        final String usedValue;
        final int bucketLength;
        if (intBucketLength == 0) {
            bucketLength = MIN_BUCKET_LENGTH;
        } else {
            bucketLength = intBucketLength;
        }
        final int lengthValue = inOriginalValue.length();
        if (bucketLength == -1 || lengthValue < bucketLength) {
            usedValue = inOriginalValue;
        } else if (lengthValue == bucketLength) {
            usedValue = "x".repeat(bucketLength);
        } else {
            usedValue = inOriginalValue.substring(0, lengthValue - bucketLength)
                    + "x".repeat(bucketLength);
        }
        return usedValue;
    }

    /**
     * Bucketing destination file name based on suffix
     * @param strSuffix input JSON file name
     * @return Path
     */
    public static String buildDestinationFileName(final String strSuffix) {
        return strInputJsonFile.substring(
                strInputJsonFile.lastIndexOf(File.separator) + 1,
                strInputJsonFile.lastIndexOf('.'))
                + "__" + strRelevantField + "_"
                + bucketFieldValue(strSuffix)
                + strInputJsonFile.substring(strInputJsonFile.lastIndexOf('.'));
    }

    /**
     * Bucketing destination full file name based on suffix
     * @param strSuffix input JSON file name
     * @return Path
     */
    private static Path buildDestinationFullFileName(final String strSuffix) {
        return Paths.get(strOutFolder).resolve(buildDestinationFileName(strSuffix));
    }

    /**
     * checks if JSON file is of an Array type
     * @param jsonParser JSON parser
     */
    private static void checkIfJsonFileIsOfArrayType(final JsonParser jsonParser) {
        // Expect: [ { ... }, { ... }, ... ]
        if (jsonParser.nextToken() != JsonToken.START_ARRAY) {
            final String strFeedback = "Root must be a JSON array but is not";
            LogExposureClass.LOGGER.error(strFeedback);
            throw new IllegalStateException(strFeedback);
        }
    }

    /**
     * closes Current file w. feedback
     * @param crtFile file name to close
     * @param writer file pointer to close
     * @param recordCounter records written
     */
    private static void closeCurrentFile(final Path crtFile, final Writer writer, final long recordCounter) {
        try {
            writer.write(']');
            writer.close();
            if (crtFile != null) {
                final String strFeedback = String.format("I just closed file %s after %s records", crtFile, recordCounter);
                LogExposureClass.LOGGER.info(strFeedback);
            }
        } catch (IOException ei) {
            LogExposureClass.exposeInputOutputException(Arrays.toString(ei.getStackTrace()));
        }
    }

    /**
     * getting Value and Record
     * @param jsonParser big JSON file handler
     * @param jsonFactory content
     * @return splitSize
     */
    private static Properties getValueAndRecord(final JsonParser jsonParser, final JsonFactory jsonFactory) {
        final Properties properties = new Properties();
        final ByteArrayOutputStream tempBuffer = new ByteArrayOutputStream();
        final ObjectWriteContext writeContext = ObjectWriteContext.empty();
        try (JsonGenerator tempGen = jsonFactory.createGenerator(writeContext, tempBuffer)) {
            int depth = 1;
            tempGen.copyCurrentEvent(jsonParser);
            while (depth > 0) {
                final JsonToken crtRecord = jsonParser.nextToken();
                tempGen.copyCurrentEvent(jsonParser);
                if (crtRecord == JsonToken.PROPERTY_NAME && jsonParser.currentName().equals(strRelevantField)) {
                    jsonParser.nextToken(); // move to value
                    final String origValue = jsonParser.getValueAsString();
                    properties.put("Value", bucketFieldValue(origValue));
                    tempGen.copyCurrentEvent(jsonParser);
                }
                if (crtRecord == JsonToken.START_OBJECT || crtRecord == JsonToken.START_ARRAY) { depth++; }
                if (crtRecord == JsonToken.END_OBJECT || crtRecord == JsonToken.END_ARRAY) {
                    tempGen.writeRaw(System.lineSeparator());
                    depth--;
                }
            }
        }
        properties.put("Record", tempBuffer.toString(StandardCharsets.UTF_8));
        return properties;
    }

    /**
     * Split a JSON files into smaller pieces
     */
    public static void splitJsonIntoSmallerGrouped() {
        final JsonFactory jsonFactory = JsonFactory.builder().build();
        final ObjectReadContext readContext = ObjectReadContext.empty();
        FileOperationsClass.MassFileChangeSubClass.setSearchingFolder(strOutFolder); // used for Mass Change (if necessary)
        final String strFeedbackTemp = String.format("JSON file named %s will be split into smaller pieces...", strInputJsonFile);
        LogExposureClass.LOGGER.debug(strFeedbackTemp);
        String rememberedValue = null;
        long recordCounter = 0;
        // initiate JSON parsing
        try (JsonParser jsonParser = jsonFactory.createParser(readContext, Path.of(strInputJsonFile))) {
            checkIfJsonFileIsOfArrayType(jsonParser);
            Path crtFile = null;
            // Iterate through each object in the array
            while (jsonParser.nextToken() == JsonToken.START_OBJECT) {
                // Buffer the object so we can inspect the field
                final Properties objectProperties = getValueAndRecord(jsonParser, jsonFactory);
                final String crtBucketValue = objectProperties.getProperty("Value");
                final String strRecord = objectProperties.getProperty("Record");
                // Start a new output file if the field value changed
                if (!crtBucketValue.equals(rememberedValue)) {
                    if (writer != null) {
                        closeCurrentFile(crtFile, writer, recordCounter);
                        recordCounter = 0;
                    }
                    final Path outFile = buildDestinationFullFileName(crtBucketValue);
                    crtFile = outFile;
                    writeObjectStart(outFile);
                    rememberedValue = crtBucketValue;
                }
                // Write buffered object to the current output file
                writeValueToNewBufferedWriter(recordCounter, writer, strRecord);
                recordCounter++;
            }
            // Close the last writer
            if (writer != null) {
                closeCurrentFile(crtFile, writer, recordCounter);
            }
        } catch (JacksonException ej) {
            final String strFeedback = String.format("Jackson exception on... %s", Arrays.toString(ej.getStackTrace()));
            LogExposureClass.LOGGER.debug(strFeedback);
        }
    }

    /**
     * Setter for intBucketLength
     * @param inBucketLength bucket length
     */
    public static void setBucketLength(final int inBucketLength) {
        intBucketLength = inBucketLength;
    }

    /**
     * Setter for strDestinationFolder
     * @param inOutFolder destination folder
     */
    public static void setDestinationFolder(final String inOutFolder) {
        strOutFolder = inOutFolder;
    }

    /**
     * Setter for strInputJsonFile
     * @param inJsonFile input JSON file
     */
    public static void setInputJsonFile(final String inJsonFile) {
        strInputJsonFile = inJsonFile;
    }

    /**
     * Setter for strRelevantField
     * @param inRelevantField relevant
     */
    public static void setRelevantField(final String inRelevantField) {
        strRelevantField = inRelevantField;
    }

    /**
     * Write object start
     * @param outFile smaller file
     */
    private static void writeObjectStart(final Path outFile) {
        boolean isFileNew = true;
        if (Files.exists(outFile)) {
            FileOperationsClass.MassFileChangeSubClass.setOldContent("]");
            FileOperationsClass.MassFileChangeSubClass.setNewContent(",");
            FileOperationsClass.MassFileChangeSubClass.setPattern(outFile.getFileName().toString());
            FileOperationsClass.MassFileChangeSubClass.massChangeToFilesWithinFolder();
            isFileNew = false;
        }
        try {
            writer = Files.newBufferedWriter(outFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (isFileNew) {
                writer.write("[");
                writer.write(System.lineSeparator());
            }
        } catch (IOException ei) {
            LogExposureClass.exposeInputOutputException(Arrays.toString(ei.getStackTrace()));
        }
    }

    /**
     * Write value to newBufferedWriter
     * @param recordCounter important to know if preceding , is needed or not
     * @param writer NewBufferedWriter handle
     * @param strBuffer current record
     */
    private static void writeValueToNewBufferedWriter(final long recordCounter, final Writer writer, final String strBuffer) {
        try {
            if (recordCounter > 0) {
                writer.write(",");
            }
            writer.write(strBuffer);
        } catch (IOException ei) {
            LogExposureClass.exposeInputOutputException(Arrays.toString(ei.getStackTrace()));
        }
    }

    /**
     * Constructor
     */
    private JsonSplitLogicClass() {
        // intentionally blank
    }

}
