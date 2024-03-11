package ncbi.taggerOne.abbreviation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ncbi.taggerOne.T1Constants;
import ncbi.util.ProcessRunner;
import ncbi.util.Profiler;

public class Ab3PAbbreviationSource implements AbbreviationSource {

	private static final Logger logger = LoggerFactory.getLogger(Ab3PAbbreviationSource.class);

	private static final long serialVersionUID = 1L;

	private String command;
	private String commandDir;
	private File tempDir;
	private long timeout;
	private Map<String, String> emptyMap = Collections.unmodifiableMap(new HashMap<String, String>());

	public Ab3PAbbreviationSource() {
		// Empty
	}

	@Override
	public void setArgs(String... args) {
		if (args.length < 5) {
			throw new IllegalArgumentException("Ab3PAbbreviationSource must have four arguments: command, commandDir, tempDir, timeout: " + Arrays.asList(args));
		}
		command = args[1];
		commandDir = args[2];
		tempDir = new File(args[3]);
		tempDir.mkdirs();
		timeout = Long.parseLong(args[4]);
	}

	public Map<String, String> getAbbreviations(String id, String text) {
		Profiler.start("Ab3PAbbreviationSource.getAbbreviations()");
		// Check for a parenthesis
		if (text.indexOf("(") < 0) {
			Profiler.stop("Ab3PAbbreviationSource.getAbbreviations()");
			return emptyMap;
		}
		try {
			logger.debug("Getting abbreviations for: " + id);

			Profiler.start("Ab3PAbbreviationSource.getAbbreviations()@write");
			// Write text to a temp file
			String filenamePrefix = id;
			while (filenamePrefix.length() < 3) {
				filenamePrefix = "0" + filenamePrefix;
			}
			File f = File.createTempFile(filenamePrefix, ".txt", tempDir);
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), T1Constants.UTF8_FORMAT));
			writer.write(text);
			writer.close();
			Profiler.stop("Ab3PAbbreviationSource.getAbbreviations()@write");

			// Get abbreviations
			Profiler.start("Ab3PAbbreviationSource.getAbbreviations()@run");
			ProcessRunner pw = new ProcessRunner(command + " " + f.getAbsolutePath(), commandDir);
			pw.await(timeout);
			String result = pw.getResult();
			String error = pw.getError();
			logger.debug("Abbreviation result is: " + result);
			logger.debug("Abbreviation error is: " + error);
			Profiler.stop("Ab3PAbbreviationSource.getAbbreviations()@run");

			// Delete temp file
			Profiler.start("Ab3PAbbreviationSource.getAbbreviations()@delete");
			boolean deleted = f.delete();
			if (!deleted) {
				logger.warn("Ab3P temp communication file " + f.getAbsolutePath() + " could not be deleted");
			}
			Profiler.stop("Ab3PAbbreviationSource.getAbbreviations()@delete");

			// Return abbreviations found
			if (result == null || error != null) {
				Profiler.stop("Ab3PAbbreviationSource.getAbbreviations()");
				return emptyMap;
			}
			Profiler.start("Ab3PAbbreviationSource.getAbbreviations()@read");
			Map<String, String> abbreviations = new HashMap<String, String>();
			BufferedReader reader = new BufferedReader(new StringReader(result));
			// First line from Ab3P is the input text
			reader.readLine();
			String line = reader.readLine();
			while (line != null) {
				if (line.startsWith("  ")) {
					String[] split = line.trim().split("\\|");
					if (split.length > 1) {
						abbreviations.put(split[0], split[1]);
						// TODO Add support for checks similar to FileAbbreviationSource
						logger.debug("Found abbreviation pair: " + split[0] + "->" + split[1]);
					} else {
						logger.warn("Abbreviation line \"" + line + "\" did not parse into an abbreviation pair: " + Arrays.toString(split));
					}
				}
				line = reader.readLine();
			}
			reader.close();
			Profiler.stop("Ab3PAbbreviationSource.getAbbreviations()@read");
			Profiler.stop("Ab3PAbbreviationSource.getAbbreviations()");
			return abbreviations;
		} catch (IOException e) {
			Profiler.stop("Ab3PAbbreviationSource.getAbbreviations()");
			throw new RuntimeException(e);
		}
	}

}
