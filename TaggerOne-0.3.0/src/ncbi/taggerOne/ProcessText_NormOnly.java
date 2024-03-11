package ncbi.taggerOne;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import javax.xml.stream.XMLStreamException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bioc.BioCAnnotation;
import bioc.BioCCollection;
import bioc.BioCDocument;
import bioc.BioCPassage;
import bioc.io.BioCDocumentWriter;
import bioc.io.BioCFactory;
import bioc.io.woodstox.ConnectorWoodstox;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import ncbi.taggerOne.abbreviation.AbbreviationSource;
import ncbi.taggerOne.abbreviation.AbbreviationSourceProcessor;
import ncbi.taggerOne.abbreviation.FolderAbbreviationSource;
import ncbi.taggerOne.lexicon.Lexicon;
import ncbi.taggerOne.model.normalization.NormalizationModelPredictor;
import ncbi.taggerOne.model.recognition.RecognitionModelPredictor;
import ncbi.taggerOne.processing.SentenceBreaker;
import ncbi.taggerOne.processing.analysis.OutputAnalysisProcessor;
import ncbi.taggerOne.processing.postProcessing.AbbreviationPostProcessing;
import ncbi.taggerOne.processing.postProcessing.AbsoluteConsistencyPostProcessing;
import ncbi.taggerOne.processing.postProcessing.CoordinationPostProcessor;
import ncbi.taggerOne.processing.postProcessing.FalseModifierRemover;
import ncbi.taggerOne.processing.postProcessing.FilterByPattern;
import ncbi.taggerOne.processing.textInstance.AbbreviationResolverProcessor;
import ncbi.taggerOne.processing.textInstance.Annotator;
import ncbi.taggerOne.processing.textInstance.InstanceElementClearer;
import ncbi.taggerOne.processing.textInstance.InstanceElementClearer.InstanceElement;
import ncbi.taggerOne.processing.textInstance.SegmentMentionProcessor;
import ncbi.taggerOne.processing.textInstance.Segmenter;
import ncbi.taggerOne.processing.textInstance.TextInstanceProcessingPipeline;
import ncbi.taggerOne.processing.textInstance.TextInstanceProcessor;
import ncbi.taggerOne.types.AnnotatedSegment;
import ncbi.taggerOne.types.Entity;
import ncbi.taggerOne.types.MentionName;
import ncbi.taggerOne.types.Segment;
import ncbi.taggerOne.types.TextInstance;
import ncbi.taggerOne.types.Token;
import ncbi.taggerOne.util.AbbreviationResolver;
import ncbi.util.Profiler;
import ncbi.taggerOne.processing.mentionName.MentionNameProcessor;
import ncbi.taggerOne.util.RankedList;

public class ProcessText_NormOnly {

	private static final Logger logger = LoggerFactory.getLogger(ProcessText.class);
	private static final String TMP_FILE_PREFIX = "tmp";

	public static void main(String[] args) throws IOException, ClassNotFoundException, XMLStreamException {
		OptionParser parser = new OptionParser();
		// Input data
		OptionSpec<String> fileFormat = parser.accepts("fileFormat").withRequiredArg().ofType(String.class).required();
		OptionSpec<String> input = parser.accepts("input").withRequiredArg().ofType(String.class).required();
		OptionSpec<String> output = parser.accepts("output").withRequiredArg().ofType(String.class).required();
		OptionSpec<String> modelInputFilename = parser.accepts("modelInputFilename").withRequiredArg().ofType(String.class).required();
		OptionSpec<Boolean> compileModel = parser.accepts("compileModel").withRequiredArg().ofType(Boolean.class).defaultsTo(true);
		OptionSpec<Boolean> showEntityScores = parser.accepts("showEntityScores").withRequiredArg().ofType(Boolean.class).defaultsTo(false);
		OptionSpec<Boolean> reprocessInput = parser.accepts("reprocessInput").withRequiredArg().ofType(Boolean.class).defaultsTo(true);
		OptionSpec<Integer> maxSegmentLength = parser.accepts("maxSegmentLength").withRequiredArg().ofType(Integer.class);
		OptionSpec<Boolean> useSentenceBreaker = parser.accepts("useSentenceBreaker").withRequiredArg().ofType(Boolean.class).defaultsTo(true);
		OptionSpec<String> coordinationPostProcessingArgs = parser.accepts("coordinationPostProcessingArgs").withRequiredArg().ofType(String.class);
		OptionSpec<String> consistencyPostProcessingArgs = parser.accepts("consistencyPostProcessingArgs").withRequiredArg().ofType(String.class);
		OptionSpec<String> abbreviationPostProcessingArgs = parser.accepts("abbreviationPostProcessingArgs").withRequiredArg().ofType(String.class);
		OptionSpec<Boolean> usefalseModifierRemoverPostProcessing = parser.accepts("usefalseModifierRemoverPostProcessing").withRequiredArg().ofType(Boolean.class)
				.defaultsTo(false);
		OptionSpec<String> abbreviationSources = parser.accepts("abbreviationSource").withRequiredArg().ofType(String.class);
		OptionSpec<String> postProcessingPatterns = parser.accepts("postProcessingPatterns").withRequiredArg().ofType(String.class);
		// TODO Add options for post-processing
		OptionSet options = parser.parse(args);
		// TODO Validate
		logger.info("Command line options:");
		for (OptionSpec<?> spec : options.specs()) {
			StringBuilder str = new StringBuilder();
			List<String> optionNames = spec.options();
			if (optionNames.size() == 1) {
				str.append(optionNames.get(0));
			} else {
				str.append(optionNames.toString());
			}
			str.append(" = ");
			List<?> values = spec.values(options);
			if (values.size() == 1) {
				str.append(values.get(0).toString());
			} else {
				str.append(values.toString());
			}
			logger.info("\t" + str.toString());
		}

		// Load the annotation pipeline
		logger.info("Loading model");
		long start = System.currentTimeMillis();
		ObjectInputStream ois = new ObjectInputStream(new GZIPInputStream(new FileInputStream(options.valueOf(modelInputFilename))));
		TextInstanceProcessingPipeline originalAnnotationPipeline = (TextInstanceProcessingPipeline) ois.readObject();
		ois.close();
		List<TextInstanceProcessor> originalProcessors = originalAnnotationPipeline.getProcessors();
		if (options.has(maxSegmentLength)) {
			Segmenter segmenter = (Segmenter) originalProcessors.get(1);
			int currentMaxLength = segmenter.getMaxLength();
			if (currentMaxLength < options.valueOf(maxSegmentLength)) {
				logger.info("Increasing maximum segment length from " + currentMaxLength + " to " + options.valueOf(maxSegmentLength));
				segmenter.setMaxLength(options.valueOf(maxSegmentLength));
			} else {
				logger.info("Retaining current maximum segment length (" + currentMaxLength + ")");
			}
		}
		AbbreviationResolverProcessor abbreviationResolverProcessor = (AbbreviationResolverProcessor) originalProcessors.get(3);
		AbbreviationResolver abbreviationResolver = abbreviationResolverProcessor.getAbbreviationResolver();
		SegmentMentionProcessor segmentMentionProcessor = (SegmentMentionProcessor) originalProcessors.get(4);
		Annotator originalAnnotator = (Annotator) originalProcessors.get(5);
		Lexicon lexicon = originalAnnotator.getLexicon();
		Map<String, NormalizationModelPredictor> originalNormalizationPredictorModels = originalAnnotator.getNormalizationModels();
		TextInstanceProcessingPipeline annotationPipeline = originalAnnotationPipeline;
		Map<String, NormalizationModelPredictor> normalizationPredictorModels = originalNormalizationPredictorModels;
		logger.info("Elapsed = " + (System.currentTimeMillis() - start));

		// MentionNameProcessor mentionNameProcessor = null;
		// for (int i = 0; i < originalProcessors.size(); i++) {
		// 	TextInstanceProcessor processor = originalProcessors.get(i);
		// 	System.out.println("TextInstanceProcessor[" + i + "]: " + processor.getClass().getSimpleName());
		// 	if (processor instanceof SegmentMentionProcessor) {
		// 		mentionNameProcessor = ((SegmentMentionProcessor)processor).getProcessor();
		// 		System.out.println("MentionNameProcessor: " + mentionNameProcessor.getClass().getSimpleName());
		// 	}
		// }
		// MentionName testMention = new MentionName("immunologic deficiency syndromes");
		// System.out.println("Mention = " + testMention.getName());
		// mentionNameProcessor.process(testMention);
		// System.out.println("tokens = " + testMention.getTokens());
		// System.out.println("vector = " + testMention.getVector());
		// 
		// NormalizationModelPredictor diseaseNormalizer = normalizationPredictorModels.get("Disease");
		// System.out.println("diseaseNormalizer = " + diseaseNormalizer);
		// RankedList<Entity> bestEntities = new RankedList<Entity>(10);
		// diseaseNormalizer.findBest(testMention.getVector(), bestEntities);
		// for (int i = 0; i < bestEntities.size(); i++) {
		// 	Entity entity = bestEntities.getObject(i);
		// 	System.out.println("" + i + ": " + bestEntities.getValue(i) + " " + entity.getPrimaryName().getName() + ", " + entity.getPrimaryIdentifier());
		// }
		// System.exit(0);

		System.out.println("1");

		// Prepare abbreviations source
		logger.info("Loading abbreviation source");
		start = System.currentTimeMillis();
		List<AbbreviationSource> abbreviationSourceList = new ArrayList<AbbreviationSource>();
		try {
			for (String abbreviationSourceConfig : options.valuesOf(abbreviationSources)) {
				String[] fields = abbreviationSourceConfig.split("\\|");
				AbbreviationSource source = (AbbreviationSource) Class.forName(fields[0]).newInstance();
				source.setArgs(fields);
				abbreviationSourceList.add(source);
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
		List<TextInstanceProcessor> processors = new ArrayList<TextInstanceProcessor>();
		processors.add(new AbbreviationSourceProcessor(abbreviationSourceList, abbreviationResolver));
		processors.addAll(originalProcessors);
		logger.info("Number of abbreviations = " + abbreviationResolver.size());
		logger.info("Elapsed = " + (System.currentTimeMillis() - start));

		System.out.println("2");

		// Compile model
		if (options.valueOf(compileModel)) {
			logger.info("Compiling model");
			RecognitionModelPredictor recognitionModel = originalAnnotator.getRecognitionModel().compile();
			normalizationPredictorModels = new HashMap<String, NormalizationModelPredictor>();
			for (String entityType : originalNormalizationPredictorModels.keySet()) {
				NormalizationModelPredictor originalPredictor = originalNormalizationPredictorModels.get(entityType);
				NormalizationModelPredictor newPredictor = originalPredictor.compile();
				normalizationPredictorModels.put(entityType, newPredictor);
			}
			Annotator annotator = new Annotator(lexicon, recognitionModel, normalizationPredictorModels);
			processors.set(6, annotator);
			logger.info("Elapsed = " + (System.currentTimeMillis() - start));
		}
		processors.add(new InstanceElementClearer(EnumSet.of(InstanceElement.PredictedStates)));
		processors.add(new MemoryReclaimer());
		if (options.valueOf(usefalseModifierRemoverPostProcessing)) {
			logger.info("falseModifierRemoverPostProcessing enabled");
			Set<String> falseModifiers = new HashSet<String>();
			falseModifiers.add("absence of");
			falseModifiers.add("absence of any");
			FalseModifierRemover falseModifierRemover = new FalseModifierRemover(falseModifiers);
			processors.add(falseModifierRemover);
		} else {
			logger.info("falseModifierRemoverPostProcessing disabled");
		}
		if (options.has(postProcessingPatterns)) {
			logger.info("postProcessingPatterns enabled");
			String patternStr = options.valueOf(postProcessingPatterns).replaceAll("\\\\\\*", "*");
			String[] postProcessingPatternArray = patternStr.split("\\|");
			processors.add(new FilterByPattern(postProcessingPatternArray));
		} else {
			logger.info("postProcessingPatterns disabled");
		}
		processors.add(new OutputAnalysisProcessor());
		if (logger.isDebugEnabled()) {
			for (int processorIndex = 0; processorIndex < processors.size(); processorIndex++) {
				logger.debug("AnnotationPipeline processor #" + processorIndex + " = " + processors.get(processorIndex).getClass().getCanonicalName());
			}
		}
		annotationPipeline = new TextInstanceProcessingPipeline(processors);
		ProcessingTimer processingPipeline = new ProcessingTimer("AnnotationPipeline", annotationPipeline);

		System.out.println("3");

		CoordinationPostProcessor coordinationPostProcessor = null;
		if (options.has(coordinationPostProcessingArgs)) {
			logger.info("coordinationPostProcessor enabled");
			coordinationPostProcessor = new CoordinationPostProcessor(normalizationPredictorModels, segmentMentionProcessor.getProcessor());
			coordinationPostProcessor.setArgs(options.valueOf(coordinationPostProcessingArgs).split("\\|"));
		} else {
			logger.info("coordinationPostProcessor disabled");
		}

		System.out.println("4");

		AbbreviationPostProcessing abbreviationPostProcessing = null;
		if (options.has(abbreviationPostProcessingArgs)) {
			logger.info("abbreviationPostProcessing enabled");
			String[] ppArgs = options.valueOf(abbreviationPostProcessingArgs).split("\\|");
			int changeThreshold = Integer.parseInt(ppArgs[0]);
			int addThreshold = Integer.parseInt(ppArgs[1]);
			boolean dropIfNoExpandedPrediction = Boolean.parseBoolean(ppArgs[2]);
			abbreviationPostProcessing = new AbbreviationPostProcessing(abbreviationResolver, changeThreshold, addThreshold, dropIfNoExpandedPrediction);
		} else {
			logger.info("abbreviationPostProcessing disabled");
		}

		System.out.println("5");

		AbsoluteConsistencyPostProcessing consistencyPostProcessing = null;
		if (options.has(consistencyPostProcessingArgs)) {
			logger.info("consistencyPostProcessing enabled");
			consistencyPostProcessing = new AbsoluteConsistencyPostProcessing(lexicon.getNonEntity());
		} else {
			logger.info("consistencyPostProcessing disabled");
		}

		System.out.println("6");

		// Process file(s)
		String inputStr = options.valueOf(input);
		String outputStr = options.valueOf(output);
		System.out.println("inputStr = " + inputStr);
		System.out.println("outputStr = " + outputStr);
		File inFile = new File(inputStr);
		File outFile = new File(outputStr);

		if (inFile.isDirectory()) {
			if (!outFile.isDirectory()) {
				throw new IllegalArgumentException("If input is a directory, then output must also be a directory");
			}
			if (!inputStr.endsWith("/")) {
				inputStr = inputStr + "/";
			}
			if (!outputStr.endsWith("/")) {
				outputStr = outputStr + "/";
			}
			File[] listOfFiles = (new File(inputStr)).listFiles();
			for (int i = 0; i < listOfFiles.length; i++) {
				if (listOfFiles[i].isFile()) {
					String currentFilename = listOfFiles[i].getName();
					String inputFilename = inputStr + currentFilename;
					File outputFile = new File(outputStr + currentFilename);
					if (!options.valueOf(reprocessInput) && outputFile.exists()) {
						logger.info("Ignoring file " + inputFilename + " because output file exists");
					} else {
						// Update abbreviations
						abbreviationResolver.clear();
						for (AbbreviationSource s : abbreviationSourceList) {
							if (s instanceof FolderAbbreviationSource) {
								FolderAbbreviationSource f = (FolderAbbreviationSource) s;
								f.loadFile(currentFilename);
							}
						}

						logger.info("Processing file " + inputFilename);
						File tempFile = File.createTempFile(TMP_FILE_PREFIX, ".txt", outFile);
						boolean moved = false;
						try {
							String tempFilename = tempFile.getAbsolutePath();
							process(options.valueOf(fileFormat), inputFilename, tempFilename, options.valueOf(useSentenceBreaker), options.valueOf(showEntityScores), processingPipeline, coordinationPostProcessor,
									abbreviationPostProcessing, consistencyPostProcessing);
							moved = tempFile.renameTo(outputFile);
							if (!moved) {
								logger.warn("Unable to move temp output file " + tempFilename + " to " + outputFile.getAbsolutePath());
							}
						} catch (Exception e) {
							logger.error("Encountered exception while processing file " + inputFilename + ": " + e.toString());
						} finally {
							if (!moved) {
								boolean deleted = tempFile.delete();
								if (!deleted) {
									logger.warn("Temp output file " + tempFile.getAbsolutePath() + " could not be deleted");
								}
							}
						}
					}
				}
			}
		} else {
			if (outFile.isDirectory()) {
				throw new IllegalArgumentException("If input is a file, then output must also be a file");
			}
			if (!options.valueOf(reprocessInput)) {
				logger.warn("Option \"reprocessInput\" is ignored for single files");
			}
			process(options.valueOf(fileFormat), inputStr, outputStr, options.valueOf(useSentenceBreaker), options.valueOf(showEntityScores), processingPipeline, coordinationPostProcessor,
					abbreviationPostProcessing, consistencyPostProcessing);
		}
		Profiler.print("\t");
		System.out.println("Done.");
	}

	private static void process(String fileFormat, String inputFilename, String outputFilename, boolean useSentenceBreaker, boolean showEntityScores, TextInstanceProcessor processingPipeline,
			CoordinationPostProcessor coordinationPostProcessor, AbbreviationPostProcessing abbreviationPostProcessing, AbsoluteConsistencyPostProcessing consistencyPostProcessing)
			throws XMLStreamException, IOException {
		if (fileFormat.toLowerCase(Locale.US).equals("pubtator")) {
			processPubtator(inputFilename, outputFilename, useSentenceBreaker, processingPipeline, coordinationPostProcessor, abbreviationPostProcessing,
					consistencyPostProcessing);
		} else if (fileFormat.toLowerCase(Locale.US).equals("bioc")) {
			processBioC(inputFilename, outputFilename, useSentenceBreaker, showEntityScores, processingPipeline, coordinationPostProcessor, abbreviationPostProcessing, consistencyPostProcessing);
		} else if (fileFormat.toLowerCase(Locale.US).equals("tsv")) {
			processTSV(inputFilename, outputFilename, useSentenceBreaker, processingPipeline, coordinationPostProcessor, abbreviationPostProcessing, consistencyPostProcessing);
		} else {
			throw new RuntimeException("File format must be BioC or Pubtator = " + fileFormat);
		}
	}

	private static void processBioC(String inputFilename, String outputFilename, boolean useSentenceBreaker, boolean showEntityScores, TextInstanceProcessor processingPipeline,
			CoordinationPostProcessor coordinationPostProcessor, AbbreviationPostProcessing abbreviationPostProcessing, AbsoluteConsistencyPostProcessing consistencyPostProcessing)
			throws XMLStreamException, IOException {

		// Open BioC file for input
		ConnectorWoodstox connector = new ConnectorWoodstox();
		BioCCollection collection = connector.startRead(new InputStreamReader(new FileInputStream(inputFilename), T1Constants.UTF8_FORMAT));

		// Open BioC file for output
		String bioCParser = BioCFactory.WOODSTOX;
		BioCFactory factory = BioCFactory.newFactory(bioCParser);
		BioCDocumentWriter writer = factory.createBioCDocumentWriter(new OutputStreamWriter(new FileOutputStream(outputFilename), T1Constants.UTF8_FORMAT));
		writer.writeCollectionInfo(collection);

		// Iterate through documents
		while (connector.hasNext()) {
			BioCDocument document = connector.next();
			String documentId = document.getID();
			logger.info("ID=" + documentId);

			List<PassageAndInstances> passages = new ArrayList<PassageAndInstances>();
			List<TextInstance> instances = new ArrayList<TextInstance>();
			int annotationIdCounter = 0;

			// Load passages & break into sentences
			for (BioCPassage passage : document.getPassages()) {
				// Get instance
				List<TextInstance> instancesForPassage = new ArrayList<TextInstance>();
				int offset = passage.getOffset();
				TextInstance instance = new TextInstance(null, documentId, documentId, passage.getText(), offset);
				instance.setTargetAnnotation(new ArrayList<AnnotatedSegment>());
				instancesForPassage.add(instance);
				// Break into sentences
				if (useSentenceBreaker) {
					SentenceBreaker sentenceBreaker = new SentenceBreaker();
					instancesForPassage = sentenceBreaker.breakSentences(instancesForPassage);
				}
				// Add to lists
				passages.add(new PassageAndInstances(passage, instancesForPassage));
				instances.addAll(instancesForPassage);
			}

			// Process document
			processingPipeline.processAll(instances);
			if (coordinationPostProcessor != null) {
				coordinationPostProcessor.processAll(instances);
			}
			if (abbreviationPostProcessing != null) {
				abbreviationPostProcessing.processAll(instances);
			}
			if (consistencyPostProcessing != null) {
				consistencyPostProcessing.processAll(instances);
			}

			// Write annotations to passages
			for (PassageAndInstances passageAndInstances : passages) {
				BioCPassage passage = passageAndInstances.getPassage();
				passage.getAnnotations().clear();
				List<TextInstance> instancesForPassage = passageAndInstances.getInstances();

				for (TextInstance instance2 : instancesForPassage) {
					List<AnnotatedSegment> predictedAnnotation = instance2.getPredictedAnnotations().getObject(0);
					for (AnnotatedSegment segment : predictedAnnotation) {
						BioCAnnotation annotation = new BioCAnnotation();
						annotation.setID(Integer.toString(annotationIdCounter));
						Map<String, String> infons = new HashMap<String, String>();
						infons.put("type", segment.getEntityClass());
						String identifiers = visualizeIdentifiers(segment.getEntities());
						if (identifiers != null) {
							infons.put("identifier", identifiers);
							if (showEntityScores) {
								infons.put("score", Double.toString(segment.getEntityScore()));
							}
						}
						annotation.setInfons(infons);
						annotation.setLocation(instance2.getOffset() + segment.getStartChar(), segment.getEndChar() - segment.getStartChar());
						annotation.setText(segment.getText());
						annotationIdCounter++;
						passage.addAnnotation(annotation);
					}
				}
			}
			writer.writeDocument(document);
		}
		writer.close();
	}

	private static class PassageAndInstances {

		private BioCPassage passage;
		private List<TextInstance> instances;

		public PassageAndInstances(BioCPassage passage, List<TextInstance> instances) {
			this.passage = passage;
			this.instances = instances;
		}

		public List<TextInstance> getInstances() {
			return instances;
		}

		public BioCPassage getPassage() {
			return passage;
		}

	}

	private static String visualizeIdentifiers(Set<Entity> entities) {
		List<String> entityIDs = new ArrayList<String>();
		for (Entity entity : entities) {
			if (entity != null) {
				String primaryIdentifier = entity.getPrimaryIdentifier();
				if (!primaryIdentifier.startsWith(T1Constants.UNKNOWN_ENTITY_ID_PREFIX) && !primaryIdentifier.equals(T1Constants.NONENTITY_STATE)) {
					entityIDs.add(primaryIdentifier);
				}
			}
		}
		if (entityIDs.size() == 0) {
			return null;
		}
		Collections.sort(entityIDs);
		StringBuilder identifiers = new StringBuilder();
		identifiers.append(entityIDs.get(0));
		for (int i = 1; i < entityIDs.size(); i++) {
			identifiers.append("|");
			identifiers.append(entityIDs.get(i));
		}
		return identifiers.toString();
	}

	private static void processPubtator(String inputFilename, String outputFilename, boolean useSentenceBreaker, TextInstanceProcessor processingPipeline,
			CoordinationPostProcessor coordinationPostProcessor, AbbreviationPostProcessing abbreviationPostProcessing, AbsoluteConsistencyPostProcessing consistencyPostProcessing)
			throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFilename), T1Constants.UTF8_FORMAT));
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFilename), T1Constants.UTF8_FORMAT));
		String line = reader.readLine();
		Map<String, String> titles = new HashMap<String, String>();
		while (line != null) {
			line = line.trim();
			String[] fields = line.split("\\|");
			if (fields.length == 2 || fields.length == 3) {
				String id = fields[0];
				String type = fields[1];
				String text = "";
				if (fields.length == 3) {
					text = fields[2];
				}

				if (type.equals("t")) {
					writer.write(id + "|t|" + text + "\n");
					// Store title
					titles.put(id, text);
				} else if (type.equals("a")) {
					writer.write(id + "|a|" + text + "\n");
					// Process abstract
					String title = titles.get(id);
					List<TextInstance> instances = new ArrayList<TextInstance>();
					TextInstance instance = new TextInstance(null, id, id, title + " " + text, 0);
					instance.setTargetAnnotation(new ArrayList<AnnotatedSegment>());
					instances.add(instance);
					// Break into sentences
					if (useSentenceBreaker) {
						SentenceBreaker sentenceBreaker = new SentenceBreaker();
						instances = sentenceBreaker.breakSentences(instances);
					}
					// Process
					processingPipeline.processAll(instances);
					if (coordinationPostProcessor != null) {
						coordinationPostProcessor.processAll(instances);
					}
					if (abbreviationPostProcessing != null) {
						abbreviationPostProcessing.processAll(instances);
					}
					if (consistencyPostProcessing != null) {
						consistencyPostProcessing.processAll(instances);
					}
					for (TextInstance instance2 : instances) {
						List<AnnotatedSegment> predictedAnnotation = instance2.getPredictedAnnotations().getObject(0);
						for (AnnotatedSegment segment : predictedAnnotation) {
							int start = instance2.getOffset() + segment.getStartChar();
							int end = instance2.getOffset() + segment.getEndChar();
							writer.write(id + "\t" + start + "\t" + end + "\t" + segment.getText() + "\t");
							String identifiers = visualizeIdentifiers(segment.getEntities());
							if (identifiers == null) {
								writer.write(segment.getEntityClass() + "\n");
							} else {
								writer.write(segment.getEntityClass() + "\t" + Entity.visualizePrimaryIdentifiers(segment.getEntities()) + "\n");
							}
						}
					}
					writer.write("\n");
				}
			}
			line = reader.readLine();
		}
		reader.close();
		writer.close();
	}

	private static void processTSV(String inputFilename, String outputFilename, boolean useSentenceBreaker, TextInstanceProcessor processingPipeline,
			CoordinationPostProcessor coordinationPostProcessor, AbbreviationPostProcessing abbreviationPostProcessing, AbsoluteConsistencyPostProcessing consistencyPostProcessing)
			throws IOException {

		List<TextInstanceProcessor> originalProcessors = ((TextInstanceProcessingPipeline)(((ProcessingTimer)processingPipeline).getProcessor())).getProcessors();
		Map<String, NormalizationModelPredictor> normalizationPredictorModels = null;
		MentionNameProcessor mentionNameProcessor = null;
		AbbreviationResolver abbreviationResolver = null;
		List<AbbreviationSource> abbreviationSourceList = null;
		for (int i = 0; i < originalProcessors.size(); i++) {
			TextInstanceProcessor processor = originalProcessors.get(i);
			System.out.println("TextInstanceProcessor[" + i + "]: " + processor.getClass().getSimpleName());
			if (processor instanceof Annotator) {
				normalizationPredictorModels = ((Annotator)processor).getNormalizationModels();
				System.out.println("Normalization models: " + normalizationPredictorModels.getClass().getSimpleName());
			}
			if (processor instanceof AbbreviationSourceProcessor) {
				abbreviationSourceList = ((AbbreviationSourceProcessor)processor).getAbbreviationSourceList();
				abbreviationResolver = ((AbbreviationSourceProcessor)processor).getAbbreviationResolver();
				System.out.println("AbbreviationResolver: " + abbreviationResolver.getClass().getSimpleName() + " size = " + abbreviationResolver.size());
			}
			if (processor instanceof SegmentMentionProcessor) {
				mentionNameProcessor = ((SegmentMentionProcessor)processor).getProcessor();
				System.out.println("MentionNameProcessor: " + mentionNameProcessor.getClass().getSimpleName());
			}
		}
		
		System.out.println("Abbreviation test = " + abbreviationResolver.expandAbbreviations("18457324", "CHF"));

		BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFilename), T1Constants.UTF8_FORMAT));
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFilename), T1Constants.UTF8_FORMAT));
		String line = reader.readLine();
		Map<String, String> titles = new HashMap<String, String>();
		while (line != null) {
			line = line.trim();
			String[] fields = line.split("\t");
			if (fields.length != 3) {
				throw new RuntimeException("Number of fields must be 3: " + fields.length);
			}
			String documentId = fields[0];
			String mentionText = fields[1];
			String type = fields[2];
			// String identifier = fields[3];
			
			abbreviationResolver.clear();
			for (AbbreviationSource source : abbreviationSourceList) {
				Map<String, String> abbreviations = source.getAbbreviations(documentId, null);
				abbreviationResolver.addAbbreviations(documentId, abbreviations);
			}
			
			System.out.println("documentId = " + documentId);
			System.out.println("Mention = " + mentionText);
			String expandedText = abbreviationResolver.expandAbbreviations(documentId, mentionText);
			System.out.println("Expanded = " + expandedText);
			MentionName mentionName = new MentionName(expandedText);
			mentionNameProcessor.process(mentionName);
			// System.out.println("tokens = " + mentionName.getTokens());
			//System.out.println("vector = " + mentionName.getVector());
			
			NormalizationModelPredictor normalizationModel = normalizationPredictorModels.get(type);
			// int maxRank = 100;
			RankedList<Entity> bestEntities = new RankedList<Entity>(1);
			normalizationModel.findBest(mentionName.getVector(), bestEntities);
			// int rank = -1;
			// for (int i = 0; i < bestEntities.size() && rank == -1; i++) {
			// 	Entity entity = bestEntities.getObject(i);
			// 	if (entity.getIdentifiers().contains(identifier)) {
			// 		rank = i;
			// 	}
			// }
			writer.write(documentId + "\t" + mentionText + "\t" + type);
			if (bestEntities.size() == 0) {
				writer.write("\t\t\n");
			} else {
				Entity entity = bestEntities.getObject(0);
				writer.write("\t" + entity.getPrimaryIdentifier() + "\t" + entity.getPrimaryName().getName() + "\n");
			}
			line = reader.readLine();
		}
		reader.close();
		writer.close();
	}

	private static class ProcessingTimer extends TextInstanceProcessor {

		private static final long serialVersionUID = 1L;

		private String timerName;
		private TextInstanceProcessor wrappedProcessor;

		public ProcessingTimer(String timerName, TextInstanceProcessor wrappedProcessor) {
			this.timerName = timerName;
			this.wrappedProcessor = wrappedProcessor;
		}

		public TextInstanceProcessor getProcessor() {
			return wrappedProcessor;
		}

		@Override
		public void process(TextInstance input) {
			Profiler.start(timerName + ".process()");
			wrappedProcessor.process(input);
			Profiler.stop(timerName + ".process()");
		}

		@Override
		public void processAll(List<TextInstance> input) {
			Profiler.start(timerName + ".processAll()");
			wrappedProcessor.processAll(input);
			Profiler.stop(timerName + ".processAll()");
		}

	}

	private static class MemoryReclaimer extends TextInstanceProcessor {

		private static final long serialVersionUID = 1L;

		@Override
		public void process(TextInstance input) {
			for (Token token : input.getTokens()) {
				token.setFeatures(null);
			}
			for (Segment segment : input.getSegments()) {
				segment.setFeatures(null);
				MentionName mentionName = segment.getMentionName();
				mentionName.setName(null);
				mentionName.setVector(null);
			}
		}
	}
}
